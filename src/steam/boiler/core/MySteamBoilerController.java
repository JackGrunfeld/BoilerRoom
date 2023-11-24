package steam.boiler.core;


import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import steam.boiler.model.SteamBoilerController;
import steam.boiler.util.Mailbox;
import steam.boiler.util.Mailbox.Message;
import steam.boiler.util.Mailbox.MessageKind;
import steam.boiler.util.Mailbox.Mode;
import steam.boiler.util.SteamBoilerCharacteristics;



/**
* Captures the various modes in which the controller can operate.
*
* @author David J. Pearce
*
*/
public class MySteamBoilerController implements SteamBoilerController {

  private enum State {
    WAITING, READY, NORMAL, DEGRADED, RESCUE, EMERGENCY_STOP;

    @Override
    public @NonNull String toString() {
      String s = super.toString();
      // Manually override non-null checker
      assert s != null;
      // Now its NonNull!
      return s;

    }
  }

  /** The boiler characteristics. */
  private final SteamBoilerCharacteristics configuration;

  /** The current mode in which the controller is operating. */
  private State mode = State.WAITING;

  /** The predicted water level. */
  private double predictedWaterLevel;

  /** The remaining amount of steam. */
  private double steamRemainder;

  /** The state of the pumps in the previous iteration. */
  private boolean lastPumpState[];

  /** The number of failed pumps. */
  private int failedPump;

  /**
   * Construct a steam boiler controller for a given set of characteristics.
   *
   * @param configuration The boiler characteristics to be used.
   */
  public MySteamBoilerController(SteamBoilerCharacteristics configuration) {
    this.configuration = configuration;
    this.lastPumpState = new boolean[this.configuration.getNumberOfPumps()];
    this.predictedWaterLevel = 0;
    this.steamRemainder = 0;
  }

  /**
   * This message is displayed in the simulation window, and enables a limited
   * form of debug output. The content of the message has no material effect on
   * the system, and can be whatever is desired. In principle, however, it should
   * display a useful message indicating the current state of the controller.
   *
   * @return Status message of steam boiler controller.
   */
  @Override
  public String getStatusMessage() {
    return this.mode.toString();
  }

  /**
   * Process a clock signal which occurs every 5 seconds. This requires reading
   * the set of incoming messages from the physical units and producing a set of
   * output messages which are sent back to them.
   *
   * @param incoming The set of incoming messages from the physical units.
   * @param outgoing Messages generated during the execution of this method should
   *                 be written here.
   * 
   * 
   */
  @Override
  public void clock(@NonNull Mailbox incoming, @NonNull Mailbox outgoing) {
    // Extract expected messages
    
    Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
    Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
    Message[] pumpStateMessages = extractAllMatches(MessageKind.PUMP_STATE_n_b, incoming);
    Message[] pumpControlStateMessages = extractAllMatches(MessageKind.PUMP_CONTROL_STATE_n_b,
        incoming);
    if (transmissionFailure(levelMessage, steamMessage, pumpStateMessages, pumpControlStateMessages,
        outgoing)) {
      // Level and steam messages required, so emergency stop.
      this.mode = State.EMERGENCY_STOP;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
    } else {
      checkPumpStatus(pumpStateMessages, pumpControlStateMessages, outgoing, incoming);
      checkControlerStatus(pumpStateMessages, pumpControlStateMessages, outgoing);
      double pumpCapacity = getPumpCapacity(pumpStateMessages);
      double levelMes = extractOnlyMatch(MessageKind.LEVEL_v, incoming).getDoubleParameter();
      double levelAvg = (minLevel(outgoing, incoming, pumpCapacity, levelMes)
          + maxLevel(outgoing, incoming, pumpCapacity, levelMes)) / 2;

      // FIXME: this is where the main implementation stems from
      // enters a state waiting for response
      if (this.mode == State.WAITING) {
        if (levelMes > this.configuration.getCapacity()) {
          this.mode = State.EMERGENCY_STOP;
        }
        initBoiler(incoming, outgoing, pumpStateMessages, steamMessage, levelMessage, levelMes,
            pumpControlStateMessages);
      } else {
        generalChecks(outgoing, outgoing, levelAvg, levelAvg, steamMessage, steamMessage,
            pumpControlStateMessages, pumpControlStateMessages);
      }
      if (this.mode == State.READY || this.mode == State.NORMAL) {
        normalStage(incoming, outgoing, pumpStateMessages, levelAvg, pumpCapacity);
      }
      if (this.mode == State.DEGRADED) {
        degradedStage(incoming, outgoing, levelMessage, steamMessage, pumpStateMessages);
      }
      if (this.mode == State.RESCUE) {
        rescueState(incoming, outgoing, levelMessage, steamMessage, pumpStateMessages);
      }
      if (this.mode == State.EMERGENCY_STOP) {
        emergancyStop(incoming, outgoing);
      }
    }
  }

  /**
   * Performs general checks on the system based on the given parameters.
   *
   * @param outgoing                 The mailbox to send outgoing messages to.
   * @param incoming                 The mailbox to receive incoming messages
   *                                 from.
   * @param levelMes                 The current level measurement.
   * @param levelAvg                 The average level measurement.
   * @param steamMessage             The steam message to check.
   * @param levelMessage             The level message to check.
   * @param pumpStateMessages        An array of pump state messages to check.
   * @param pumpControlStateMessages An array of pump control state messages to
   *                                 check.
   */
  private void generalChecks(Mailbox outgoing, Mailbox incoming, double levelMes, double levelAvg,
      Message steamMessage, Message levelMessage, Message[] pumpStateMessages,
      Message[] pumpControlStateMessages) {
    if (levelMes == this.configuration.getMinimalLimitLevel()) {
      emergancyStop(incoming, outgoing);
      outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
    }
    if (levelMes < 0.0 || levelMes > this.configuration.getCapacity()
        || levelMes > this.configuration.getCapacity() + levelMes + (levelMes - levelAvg)) {
      this.mode = State.RESCUE;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
      outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
    }
    if (steamMessage.getDoubleParameter() < 0.0
        || steamMessage.getDoubleParameter() >= this.configuration.getCapacity()) {
     
      this.mode = State.DEGRADED;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
      outgoing.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
    }
  }

  /**
   * This method triggers an emergency stop of the system by changing the mode to
   * emergency stop and sending messages to the outgoing mailbox to stop the
   * system and change the mode to emergency stop.
   *
   * @param incoming the incoming mailbox.
   * @param outgoing the outgoing mailbox.
   */
  private void emergancyStop(Mailbox incoming, Mailbox outgoing) {
    // Send messages to stop the system and change the mode to emergency stop
    this.mode = State.EMERGENCY_STOP;
    outgoing.send(new Message(MessageKind.STOP));
    outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
  }

  // Implement boiler logic here
  // NOTE: this is an example message send to illustrate the syntax
  // outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
  // outgoing.send(new Message(MessageKind.))
  /**
   * Initialises the boiler system with the given parameters.
   *
   * @param incoming          The input mailbox for messages.
   * @param outgoing          The output mailbox for messages.
   * @param pumpStateMessages The pump state messages to extract the pump capacity
   *                          from.
   * @param steamMessage      the steam state message to extract steam capacity
   *                          from.
   */

  private void initBoiler(Mailbox incoming, Mailbox outgoing, Message[] pumpStateMessages,
      Message steamMessage, Message levelMessage, double levelMes,
      Message[] pumpControlStateMessages) {
    System.out.println("Current mode: " + this.mode + ",Mode ment to be Initialsiation");
    outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
    if (extractOnlyMatch(MessageKind.STEAM_BOILER_WAITING, incoming) != null) {
      checkSteamUnit(steamMessage, outgoing, incoming, 0.0);
      checkLevelUnit(levelMessage, steamMessage, outgoing);
      if (levelMes < 0.0) {
        this.mode = State.RESCUE;
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
        outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
      }
      if (steamMessage.getDoubleParameter() != 0.0
          || steamMessage.getDoubleParameter() > this.configuration.getCapacity()) {
        emergancyStop(incoming, outgoing);
        outgoing.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
      }
      if (levelMes < 0 || levelMes > this.configuration.getCapacity() 
          || levelMes > this.configuration.getMaximalLimitLevel()) {
        emergancyStop(incoming, outgoing);
        outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
      }
      if (levelMes > this.configuration.getMaximalNormalLevel()) {
        outgoing.send(new Message(MessageKind.VALVE));
      }
      if (levelMes < this.configuration.getMinimalNormalLevel()
          || levelMes < this.configuration.getMaximalNormalLevel()) {
        if (this.mode == State.WAITING) {
          if (levelMes < (this.configuration.getCapacity() / 2)) {
            if (this.configuration.getNumberOfPumps() < 2) {
              outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 0));
              lastPumpState[0] = true;
              return;
            } else {
              for (int i = 0; i < 2; i++) {
                if (!pumpStateMessages[i].getBooleanParameter()) {
                  outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
                  lastPumpState[i] = true;
                  return;
                }
              }
            }
          } else {
            System.out.println("tank full");
            if (levelMes > (this.configuration.getCapacity() / 2)) {
              for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
                if (pumpStateMessages[i].getBooleanParameter()) {
                  outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
                  outgoing.send(new Message(MessageKind.VALVE));
                  lastPumpState[i] = false;
                  return;
                }
              }
            }
          }
        }
      }
      if (levelMes < this.configuration.getMaximalNormalLevel()
          && levelMes > this.configuration.getMinimalNormalLevel()) {
        outgoing.send(new Message(MessageKind.PROGRAM_READY));
        this.mode = State.READY;
      }
    }
  }

  /**
   * This method performs the equalisation stage of the system, which involves
   * determining the minimum, maximum and average levels of the incoming mailbox
   * and deciding whether to open or close the pumps based on the average level.
   *
   * @param incoming          the incoming mailbox.
   * @param outgoing          the outgoing mailbox.
   * @param pumpStateMessages an array of messages representing the pump state
   * 
   */

  private void normalStage(Mailbox incoming, Mailbox outgoing, Message[] pumpStateMessages,
      double levelMes, double pumpCapacity) {
    System.out.println("Current mode: " + this.mode + ",Mode meant to be Normal");

    Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
    Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);

    if (extractOnlyMatch(MessageKind.PHYSICAL_UNITS_READY, incoming) != null
        || this.mode == State.NORMAL) {
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
      this.mode = State.NORMAL;
      System.out.println(this.mode);

      checkSteamUnit(steamMessage, outgoing);
      checkLevelUnit(levelMessage, steamMessage, outgoing);
      checkPumpStatus(pumpStateMessages, pumpStateMessages, outgoing, outgoing);
      checkControlerStatus(pumpStateMessages, pumpStateMessages, outgoing);
      checkControlPump(outgoing, outgoing, pumpStateMessages, pumpStateMessages);
      checkPump(outgoing, outgoing, pumpStateMessages, pumpStateMessages);
      checkNormLevel(steamMessage, steamMessage, outgoing, incoming);
      // Calculate the minimum, maximum and average levels
      double levelMin = minLevel(outgoing, incoming, pumpCapacity, levelMes);
      double levelMax = maxLevel(outgoing, incoming, pumpCapacity, levelMes);
      double levelAvg = (levelMin + levelMax) / 2;

      // Check whether to open or close the pumps based on the average level
      turnPumpsOnOff(outgoing, outgoing, levelAvg, lastPumpState, pumpStateMessages);
    }
  }

  /**
   * Puts the system in degraded mode where one pump has failed and the remaining
   * pumps are adjusted to compensate.
   *
   * @param incoming          the mailbox containing incoming messages.
   * @param outgoing          the mailbox to send outgoing messages.
   * @param levelMessage      the message containing the current level
   *                          information.
   * @param steamMessage      the message containing the current steam
   *                          information.
   * @param pumpStateMessages the messages containing the pump state information.
   */

  private void degradedStage(Mailbox incoming, Mailbox outgoing, Message levelMessage,
      Message steamMessage, Message[] pumpStateMessages) {
    System.out.println("Current mode: " + this.mode + ", Mode meant to be: DEGRADED");

    // Update system mode and notify controller
    this.mode = State.DEGRADED;
    outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));

    // Notify controller of failed pump
    outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, this.failedPump));

    // Close all pumps
    for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
      outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
      this.lastPumpState[i] = false;
    }

    if (levelMessage.getDoubleParameter() >= this.configuration.getMaximalLimitLevel()
        || levelMessage.getDoubleParameter() <= this.configuration.getMinimalLimitLevel()) {
      this.mode = State.EMERGENCY_STOP;
      outgoing.send(new Message(MessageKind.MODE_m, Mode.EMERGENCY_STOP));
    }
    // Adjust remaining pumps to compensate for the failed pump
    int pumpsOpen = getPumpsOpen(levelMessage, steamMessage);

    if (pumpStateMessages[this.failedPump].getBooleanParameter()) {
      pumpsOpen--;
    }

    for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
      if (i != this.failedPump && pumpsOpen > 0) {
        outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
        this.lastPumpState[i] = true;
        pumpsOpen--;
      }
    }
  }

  /**
   * Changes the system mode to rescue mode and adjusts the pump states based on
   * the steam level.
   *
   * @param incoming          the incoming mailbox containing the messages
   *                          received from other components
   * @param outgoing          the outgoing mailbox to send messages to other
   *                          components
   * @param levelMessage      the message containing the level measurement
   * @param steamMessage      the message containing the steam measurement
   * @param pumpStateMessages the messages containing the pump states
   */
  private void rescueState(Mailbox incoming, Mailbox outgoing, Message levelMessage,
      Message steamMessage, Message[] pumpStateMessages) {
    outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
    this.mode = State.RESCUE;
    double pumpCapacity = this.configuration.getPumpCapacity(0);
    double totalSteam = steamMessage.getDoubleParameter() + this.steamRemainder;
    this.steamRemainder = totalSteam % pumpCapacity;
    turnPumpsOnOff(outgoing, outgoing, totalSteam, lastPumpState, pumpStateMessages);
  }

  /**
   * Checks the steam measurement unit and triggers an emergency stop if the unit
   * is not within the acceptable range.
   *
   * @param steamMessage the message containing the steam measurement
   * @param outgoing     the outgoing mailbox to send messages to other components
   * @param incoming     the incoming mailbox containing the messages received
   *                     from other components
   * @param steamAmount  the expected amount of steam
   */
  private void checkSteamUnit(Message steamMessage, Mailbox outgoing, Mailbox incoming,
      double steamAmount) {
    if (steamMessage.getDoubleParameter() < 0
        || steamMessage.getDoubleParameter() > this.configuration.getMaximualSteamRate()
        || steamMessage.getDoubleParameter() != steamAmount) {
      emergancyStop(incoming, outgoing);
    }
  }

  /**
   * Checks if the current steam measurement is within acceptable range. If the
   * measurement is outside of the range, the system enters degraded mode.
   *
   * @param steamMessage The current steam measurement message.
   * @param outgoing     The mailbox for sending messages.
   */

  private void checkSteamUnit(Message steamMessage, Mailbox outgoing) {
    if (steamMessage.getDoubleParameter() < 0
        || steamMessage.getDoubleParameter() > this.configuration.getMaximualSteamRate()) {
      System.out.println("D Mode at Steam!");
      this.mode = State.DEGRADED;
      outgoing.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
    }
  }

  /**
   * Gets the total pump capacity from the given pump state messages.
   *
   * @param pumpStateMessages The pump state messages to extract the pump capacity
   * @return The total pump capacity.
   */

  private double getPumpCapacity(Message[] pumpStateMessages) {
    double pumpCapacity = 0;
    for (Message pump : pumpStateMessages) {
      if (pump.getBooleanParameter()) {
        pumpCapacity += this.configuration.getPumpCapacity(pump.getIntegerParameter());
      }
    }
    return pumpCapacity;
  }

  /**
   * Calculates the predicted minimum water level in the boiler.
   * 
   *
   * @param outgoing     the outgoing mailbox
   * @param incoming     the incoming mailbox
   * @param pumpCapacity the total pump capacity
   * @param levelMes     the current water level in the boiler
   * @return the predicted minimum water level.
   */

  private double minLevel(Mailbox outgoing, Mailbox incoming, double pumpCapacity,
      double levelMes) {
    double predictLevel = 0;
    predictLevel = levelMes + (5 * pumpCapacity) - (5 * this.configuration.getMaximualSteamRate());
    return predictLevel;
  }

  /**
   * Calculates the predicted maximum water level in the boiler.
   *
   * @param outgoing     the outgoing mailbox
   * @param incoming     the incoming mailbox
   * @param pumpCapacity the total pump capacity
   * @param levelMes     the current water level in the boiler
   * @return the predicted maximum water level.
   * 
   */

  private double maxLevel(Mailbox outgoing, Mailbox incoming, double pumpCapacity,
      double levelMes) {
    double predictLevel = 0;
    predictLevel = levelMes + (5 * pumpCapacity)
        - (5 * extractOnlyMatch(MessageKind.STEAM_v, incoming).getDoubleParameter());
    return predictLevel;
  }

  /**
   * Determines whether to turn pumps on or off based on the average level.
   *
   * @param incoming          the mailbox containing incoming messages
   * @param outgoing          the mailbox for outgoing message
   * @param levelAvg          the average level of the tank
   * @param lastPumpState     the last state of the pump
   * @param pumpStateMessages an array of pump state messages
   */
  private void turnPumpsOnOff(Mailbox incoming, Mailbox outgoing, double levelAvg,
      boolean[] lastPumpState, Message[] pumpStateMessages) {
    if (levelAvg <= (this.configuration.getCapacity() / 2)) {
      for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
        if (!pumpStateMessages[i].getBooleanParameter()) {
          lastPumpState[i] = true;
          outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
          return;
        }
      }
    }

    if (levelAvg > (this.configuration.getCapacity() / 2)) {
      for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
        if (pumpStateMessages[i].getBooleanParameter()) {
          lastPumpState[i] = false;
          outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
          return;
        }
      }
    }
  }

  /**
   * Checks the state of the pumps and control pumps for any failures.
   *
   * @param incoming                 Incoming mailbox to receive messages from.
   * @param outgoing                 Outgoing mailbox to send messages to.
   * @param pumpStateMessages        Array of pump state messages.
   * @param pumpControlStateMessages Array of pump control state messages.
   */

  private void checkPump(Mailbox incoming, Mailbox outgoing, Message[] pumpStateMessages,
      Message[] pumpControlStateMessages) {
    for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
      if (this.lastPumpState[i] != pumpStateMessages[i].getBooleanParameter()
          && this.lastPumpState[i] != pumpControlStateMessages[i].getBooleanParameter()) {
        failedPump = i;
        System.out.println("D Mode at Pump");
        this.mode = State.DEGRADED;
        outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i));
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
        System.out.println("failure Dectected at Pump: " + i);
      }
    }
  }

  /**
   * Checks the state of the control pumps for any failures.
   *
   * @param incoming                 Incoming mailbox to receive messages from.
   * @param outgoing                 Outgoing mailbox to send messages to.
   * @param pumpStateMessages        Array of pump state messages.
   * @param pumpControlStateMessages Array of pump control state messages.
   */
  private void checkControlPump(Mailbox incoming, Mailbox outgoing, Message[] pumpStateMessages,
      Message[] pumpControlStateMessages) {
    for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
      if (this.lastPumpState[i] != pumpControlStateMessages[i].getBooleanParameter()) {
        System.out.println("D mode at control Pump!");
        this.mode = State.DEGRADED;
        outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i));
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
      }
    }
  }

  /**
   * Gets the number of pumps that should be open based on the water level.
   *
   * @param levelMessage Message containing the current water level.
   * @param steamMessage Message containing the current steam amount.
   * @return The number of pumps that should be open.
   */

  private int getPumpsOpen(Message levelMessage, Message steamMessage) {
    double averageLevel = (this.configuration.getMinimalNormalLevel()
        + this.configuration.getMaximalNormalLevel()) / 2;
    double minDistance = Double.MAX_VALUE;
    int numOpen = 0;
    for (int i = 0; i < this.configuration.getNumberOfPumps() + 1; i++) {
      double currAverage = (piMaxLevel(levelMessage, steamMessage, i) + piMinLevel(levelMessage, i))
          / 2;
      if (Math.abs(averageLevel - currAverage) < minDistance) {
        numOpen = i;
        minDistance = Math.abs(averageLevel - currAverage);
        this.predictedWaterLevel = currAverage;
      }
    }
    return numOpen;
  }

  /**
   * Calculates the maximum water level when a certain number of pumps are open.
   *
   * @param levelMessage The current water level message.
   * @param steamMessage The current steam measurement message.
   * @param pumpsOpen    The number of pumps that are open.
   * @return The maximum water level.
   */

  private double piMaxLevel(Message levelMessage, Message steamMessage, int pumpsOpen) {
    return levelMessage.getDoubleParameter()
        + (5 * this.configuration.getPumpCapacity(1) * pumpsOpen)
        - (5 * steamMessage.getDoubleParameter());
  }

  /**
   * Calculates the minimum water level when a certain number of pumps are open.
   *
   * @param levelMessage The current water level message.
   * @param pumpsOpen    The number of pumps that are open.
   * @return The minimum water level.
   */

  private double piMinLevel(Message levelMessage, int pumpsOpen) {
    return levelMessage.getDoubleParameter()
        + (5 * this.configuration.getPumpCapacity(1) * pumpsOpen)
        - (5 * this.configuration.getMaximualSteamRate());
  }

  /**
   * Check if the water level measurement unit is functioning correctly. If the
   * measured water level is out of the valid range, set the system's mode to
   * rescue and send a level failure detection message to the outgoing mailbox.
   * Also, print the actual and predicted water levels to the console.
   *
   * @param levelMessage The message containing the water level measurement
   * @param steamMessage The message containing the steam measurement
   * @param outgoing     The outgoing mailbox to send messages
   */

  private void checkLevelUnit(Message levelMessage, Message steamMessage, Mailbox outgoing) {
    if (levelMessage.getDoubleParameter() > this.configuration.getCapacity()
        || levelMessage.getDoubleParameter() < 0) {
      this.mode = State.RESCUE;
      outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));

      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
    }

    System.out.println("Actual: " + levelMessage.getDoubleParameter()); //$NON-NLS-1$
    System.out.println();
    System.out.println("predicted: " + this.predictedWaterLevel); //$NON-NLS-1$
  }

  /**
   * Check if the water level measurement unit is functioning correctly. If the
   * measured water level is out of the valid range, set the system's mode to
   * rescue and send a level failure detection message to the outgoing mailbox.
   * Also, print the actual and predicted water levels to the console.
   *
   * @param levelMessage The message containing the water level measurement
   * @param steamMessage The message containing the steam measurement
   * @param outgoing     The outgoing mailbox to send messages
   */
  private void checkNormLevel(Message levelMessage, Message steamMessage, Mailbox outgoing,
      Mailbox incoming) {

    if (extractOnlyMatch(MessageKind.LEVEL_v, incoming)
        .getDoubleParameter() < (this.configuration.getMinimalNormalLevel()
            - (this.configuration.getMinimalNormalLevel() / 4))) {
      this.mode = State.RESCUE;
      outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));

      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
    }
  }

  /**
   * Check the status of the pumps by comparing the current state to the previous
   * state. If there is a discrepancy in the pump state or control state, set the
   * system's mode to degraded, identify the failed pump, and send a pump failure
   * detection message to the outgoing mailbox.
   *
   * @param pumpStateMessages        The messages containing the pump states
   * @param pumpControlStateMessages The messages containing the pump control
   *                                 states
   * @param outgoing                 The outgoing mailbox to send messages
   * @param incoming                 The incoming mailbox to receive messages
   */

  private void checkPumpStatus(Message[] pumpStateMessages, Message[] pumpControlStateMessages,
      Mailbox outgoing, Mailbox incoming) {
    for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
      if (this.lastPumpState[i] != pumpStateMessages[i].getBooleanParameter()
          && this.lastPumpState[i] != pumpControlStateMessages[i].getBooleanParameter()) {
        System.out.println("D MODE PUMPstatus!");
        this.mode = State.DEGRADED;
        this.failedPump = i;
        outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i));
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
      }
    }
  }

  /**
   * Checks the status of the pump controllers.
   *
   * @param pumpStateMessages        The pump state messages.
   * @param pumpControlStateMessages The pump control state messages.
   * @param outgoing                 The mailbox for outgoing messages.
   */

  private void checkControlerStatus(Message[] pumpStateMessages, Message[] pumpControlStateMessages,
      Mailbox outgoing) {
    for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
      if (this.lastPumpState[i] != pumpControlStateMessages[i].getBooleanParameter()) {
        this.mode = State.DEGRADED;
        outgoing.send(new Message(MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n, i));
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
      }
    }
  }
  
  /**
   * Checks the status of the pump controllers.
   *
   * @param pumpStateMessages        The pump state messages.
   * @param pumpControlStateMessages The pump control state messages.
   * @param outgoing                 The mailbox for outgoing messages.
   */

  private void checkControlerStatus(Message[] pumpStateMessages, Message[] pumpControlStateMessages,
      Mailbox outgoing, int num) {
    for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
      if (this.lastPumpState[i] != pumpControlStateMessages[i].getBooleanParameter()) {
        this.mode = State.EMERGENCY_STOP;
        outgoing.send(new Message(MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n, i));
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      }
    }
  }

  /**
   * Gets the number of currently open pumps.
   *
   * @param pumpStateMessages The pump state messages.
   * @return The number of currently open pumps.
   */

  private static int getCurrPumpsOpen(Message[] pumpStateMessages) {
    int counter = 0;
    for (Message m : pumpStateMessages) {
      if (m.getBooleanParameter()) {
        counter++;
      }
    }
    return counter;
  }

  /**
   * Check whether there was a transmission failure. This is indicated in several
   * ways. Firstly, when one of the required messages is missing. Secondly, when
   * the values returned in the messages are nonsensical.
   *
   * @param levelMessage      Extracted LEVEL_v message.
   * @param steamMessage      Extracted STEAM_v message.
   * @param pumpStates        Extracted PUMP_STATE_n_b messages.
   * @param pumpControlStates Extracted PUMP_CONTROL_STATE_n_b messages.
   * @return True if a transmission failure was detected.
   */

  private boolean transmissionFailure(Message levelMessage, Message steamMessage,
      Message[] pumpStates, Message[] pumpControlStates, Mailbox outgoing) {
    // Check level readings
    if (levelMessage == null || !levelMessage.getKind().equals(MessageKind.LEVEL_v)) {

      // Nonsense or missing level reading
      return true;

    } else if (steamMessage == null || !steamMessage.getKind().equals(MessageKind.STEAM_v)) {

      // Nonsense or missing steam reading
      return true;
    } else if (pumpStates.length != this.configuration.getNumberOfPumps()) {

      // Nonsense pump state readings
      return true;
    } else if (pumpControlStates.length != this.configuration.getNumberOfPumps()) {

      // Nonsense pump control state readings
      return true;
    }
    // Done
    return false;
  }

  /**
   * Find and extract a message of a given kind in a mailbox. This must the only
   * match in the mailbox, else <code>null</code> is returned.
   *
   * @param kind     The kind of message to look for.
   * @param incoming The mailbox to search through.
   * @return The matching message, or <code>null</code> if there was not exactly
   *         one match.
   */
  private static @Nullable Message extractOnlyMatch(MessageKind kind, Mailbox incoming) {
    Message match = null;
    for (int i = 0; i != incoming.size(); ++i) {
      Message ith = incoming.read(i);
      if (ith.getKind() == kind) {
        if (match == null) {
          match = ith;
        } else {
          // This indicates that we matched more than one message of the given kind.
          return null;
        }
      }
    }
    return match;
  }

  /**
   * Find and extract all messages of a given kind.
   *
   * @param kind     The kind of message to look for.
   * @param incoming The mailbox to search through.
   * @return The array of matches, which can empty if there were none.
   */
  private static Message[] extractAllMatches(MessageKind kind, Mailbox incoming) {
    int count = 0;
    // Count the number of matches
    for (int i = 0; i != incoming.size(); ++i) {
      Message ith = incoming.read(i);
      if (ith.getKind() == kind) {
        count = count + 1;
      }
    }
    // Now, construct resulting array
    Message[] matches = new Message[count];
    int index = 0;
    for (int i = 0; i != incoming.size(); ++i) {
      Message ith = incoming.read(i);
      if (ith.getKind() == kind) {
        matches[index++] = ith;
      }
    }
    return matches;
  }
}
