package org.wikimedia.analytics.refinery.job.mediawikihistory.user

import org.apache.spark.sql.SparkSession
import org.wikimedia.analytics.refinery.core.TimestampHelpers
import org.wikimedia.analytics.refinery.spark.utils.{StatsHelper, MapAccumulator}


/**
  * This class implements the core algorithm of the user history reconstruction.

  * The [[run]] function first partitions the [[UserState]] and [[UserEvent]] RDDs
  * using [[org.wikimedia.analytics.refinery.spark.utils.SubgraphPartitioner]],
  * then applies its [[processSubgraph]] method to every partition.
  *
  * It returns the [[UserState]] RDD of joined results of every partition and either
  * the errors found on the way, or their count.
  */
class UserHistoryBuilder(
                          val spark: SparkSession,
                          val statsAccumulator: Option[MapAccumulator[String, Long]]
                          ) extends StatsHelper with Serializable {

  import org.apache.log4j.Logger
  import org.apache.spark.rdd.RDD
  import org.joda.time.{DateTime,DateTimeZone}
  import java.sql.Timestamp
  import org.wikimedia.analytics.refinery.spark.utils.SubgraphPartitioner

  @transient
  lazy val log: Logger = Logger.getLogger(this.getClass)

  val presentTimestamp = new Timestamp(DateTime.now.withZone(DateTimeZone.UTC).getMillis)
  val METRIC_EVENTS_MATCHING_OK = "users.eventsMatching.OK"
  val METRIC_EVENTS_MATCHING_KO = "users.eventsMatching.KO"

  /**
    * This case class contains the various state dictionary and list needed to
    * reconstruct the user history, as well as errors.
    * An object of this class is updated and passed along reconstruction,
    * allowing to move a single object instead of the five it contains.
    *
    * It defines the functions used in [[processEvent]] to update itself.
    *
    * @param todayToCurrent Links today usernames with currently worked states usernames
    * @param currentToToday Links currently worked states usernames with today's usernames
    * @param potentialStates States that can be joined to events
    * @param knownStates States that have already been joined to events
    * @param unmatchedEvents Events having not match any state (or their count)
    */
  case class ProcessingStatus(
      todayToCurrent: Map[UserHistoryBuilder.KEY, UserHistoryBuilder.KEY],
      currentToToday: Map[UserHistoryBuilder.KEY, UserHistoryBuilder.KEY],
      potentialStates: Map[UserHistoryBuilder.KEY, UserState],
      knownStates: Seq[UserState],
      unmatchedEvents: Seq[UserEvent]
  ) {

    /**
      * Updates the linking-usernames dictionaries with new values if needed
      *
      * @param fromKey The fromKey to be added (new old-key)
      * @param toKey The toKey to retrieve today's key (old old-key)
      * @return The updated process status
      */
    def updateNameDictionaries(fromKey: UserHistoryBuilder.KEY, toKey: UserHistoryBuilder.KEY): ProcessingStatus = {
      if (fromKey != toKey) {
        val todayKey = currentToToday.getOrElse(toKey, toKey)
        this.copy(
            todayToCurrent = todayToCurrent + (todayKey -> fromKey),
            currentToToday = currentToToday - toKey + (fromKey -> todayKey)
        )
      } else this
    }


    /**
      * Moves a potential joinable state (toKey == state.key) to known states if its user
      * registration timestamp is after the event timestamp.
      *
      * @param event The event currently worked
      * @param toKey The event toKey (with updated username)
      * @return The updated processing status
      */
    def flushExpiredState(event: UserEvent, toKey: UserHistoryBuilder.KEY): ProcessingStatus = {
      if (potentialStates.contains(toKey) &&
          event.timestamp.before(potentialStates(toKey).userRegistrationTimestamp.getOrElse(new Timestamp(0L)))) {
        val state = potentialStates(toKey)
        this.copy(
            potentialStates = potentialStates - toKey,
            knownStates = knownStates :+ state.copy(
              startTimestamp = state.userRegistrationTimestamp,
              causedByEventType = "create",
              causedByUserId = None,
              inferredFrom = Some("unclosed")
            )
        )
      } else this
    }


    /**
      * Moves a conflicting potential state (fromKey == state.key) to known states
      *
      * @param event The event currently worked
      * @param fromKey The event fromKey (with updated username)
      * @return The updated processing status
      */
    def flushConflictingState(event: UserEvent, fromKey: UserHistoryBuilder.KEY): ProcessingStatus = {
      if (event.eventType == "rename" && potentialStates.contains(fromKey)) {
        val state = potentialStates(fromKey)
        this.copy(
            potentialStates = potentialStates - fromKey,
            knownStates = knownStates :+ state.copy(
                  userRegistrationTimestamp = Some(event.timestamp),
                  startTimestamp = Some(event.timestamp),
                  causedByEventType = "create",
                  causedByUserId = None,
                  inferredFrom = Some("conflict")
              )
        )
      } else this
    }


    /**
      * joins an event and potential state (toKey == state.key)
      * by moving it from potential states to known states
      * and create a new potential state if the event is
      * not a create event
      *
      * @param event The event currently worked
      * @param fromKey The event fromKey (with updated username)
      * @param toKey The event toKey (with updated username)
      * @return The updated processing status
      */
    def joinEventWithState(
        event: UserEvent,
        fromKey: UserHistoryBuilder.KEY,
        toKey: UserHistoryBuilder.KEY
    ): ProcessingStatus = {
      if (potentialStates.contains(toKey)) {
        addOptionalStat(s"${event.wikiDb}.$METRIC_EVENTS_MATCHING_OK", 1)
        val state = potentialStates(toKey)
        this.copy(
            potentialStates =
              if (event.eventType == "create") potentialStates - toKey
              else
                potentialStates - toKey + (fromKey -> state.copy(
                        userNameHistorical = fromKey._2,
                        endTimestamp = Some(event.timestamp)
                    )),
            knownStates = knownStates :+ state.copy(
                  startTimestamp = Some(event.timestamp),
                  userGroupsHistorical = event.newUserGroups,
                  userBlocksHistorical = event.newUserBlocks,
                  createdBySelf = event.createdBySelf,
                  createdBySystem = event.createdBySystem,
                  createdByPeer = event.createdByPeer,
                  causedByEventType = event.eventType,
                  causedByUserId = event.causedByUserId,
                  causedByBlockExpiration = event.blockExpiration
              )
        )
      } else {
        // No event match - updating accumulator and errors
        addOptionalStat(s"${event.wikiDb}.$METRIC_EVENTS_MATCHING_KO", 1)
        this.copy(unmatchedEvents = this.unmatchedEvents :+ event)
      }
    }
  }

  /**
    * Updates usernames from process status dictionaries
    * depending on event type
    *
    * @param event The event to update usernames for
    * @param status The processing status to use for dictionaries
    * @return The correct usernames as a (fromKey, toKey) pair
    */
  def fixedEventNames(
      event: UserEvent,
      status: ProcessingStatus
  ): (UserHistoryBuilder.KEY, UserHistoryBuilder.KEY) = {
    val toKey = (event.wikiDb, event.newUserName)
    if (event.eventType == "rename") {
      val fromKey = (event.wikiDb, event.oldUserName)
      (fromKey, toKey)
    } else {
      val currentKey = status.todayToCurrent.getOrElse(toKey, toKey)
      (currentKey, currentKey)
    }
  }

  /**
    * Updates a processing status with an event (Used in [[processSubgraph]]
    * through a foldLeft).
    *
    * - updates the linking-usernames dictionaries (needed because
    *   some of the events of the table use old usernames, and others today's usernames),
    * - flushes (moves to known states) a joinable state (event.toKey == state.key)
    *   if its user registration timestamp is after the event timestamp.
    * - flushes conflicting state (event.fromKey == state.key) if any
    * - joins the event and state in a new returned processing status
    *
    * @param status The processing status to update - It contains
    *               the states to potentially join to
    * @param event The event used to update the processing status.
    * @return The updated processing status.
    */
  def processEvent(
      status: ProcessingStatus,
      event: UserEvent
  ): ProcessingStatus = {
    val (fromKey, toKey) = fixedEventNames(event, status)
    status
      .updateNameDictionaries(fromKey, toKey)
      .flushExpiredState(event, toKey)
      .flushConflictingState(event, fromKey)
      .joinEventWithState(event, fromKey, toKey)
  }


  /**
    * Loops through states updating their anonymous
    * and botByName values.
    *
    * @param states The states to update
    * @return The updated states
    */
  def updateAnonymousAndBotByName(states: List[UserState]): List[UserState] = {
    states.map(s => s.copy(
      anonymous = s.userId == 0,
      botByName = UserEventBuilder.isBotByName(s.userNameHistorical)
    ))
  }


  /**
    * Propagate user registration and createdBy from first state to every next ones
    *
    * @param states The states to update (single user,  ordered by timestamp)
    * @return The updated states
    */
  def propagateUserRegistrationAndCreatedBy(states: List[UserState]): List[UserState] = {
    states.map(s => s.copy(
      userRegistrationTimestamp = states.head.userRegistrationTimestamp,
      createdBySelf = states.head.createdBySelf,
      createdBySystem = states.head.createdBySystem,
      createdByPeer = states.head.createdByPeer
    ))
  }


  /**
    * Propagate historical user groups from left (before) to right (after), and then
    * backward for most recent user groups.
    *
    * @param states The states to update (single user, ordered by timestamp)
    * @return The updated states
    */
  def propagateUserGroups(states: List[UserState]): List[UserState] = {
    val propagated = states
      .tail
      .foldLeft((List(states.head), Seq.empty[String])) {
        case ((processed, userGroups), state) =>
          if (state.causedByEventType == "altergroups") {
            (processed :+ state, state.userGroupsHistorical)
          } else {
            (processed :+ state.copy(userGroupsHistorical = userGroups), userGroups)
          }
      }
      ._1
    // Propagate mostRecentGroups from right to left.
    val mostRecentGroups = propagated.last.userGroups
    propagated.map(s => s.copy(userGroups = mostRecentGroups))
  }


  /**
    * Populate historical user blocks from left (before) to right (after) and then
    * backward for most recent user blocks.
    *
    * This function is a bit more complex because it needs to create a new
    * state in case of block expiration. This also means it needs to be
    * applied first among other propagation / update functions.
    *
    * @param states The states to update (single user, ordered by timestamp)
    * @return The updated states
    */
  def propagateUserBlocks(states: List[UserState]): List[UserState] = {
    val propagated = states
      // Initialises userBlocks to states first element ones and fold on the rest of states
      .tail.foldLeft(
        (List(states.head), // The list of worked states
          states.head.userBlocksHistorical, // The userBlocks to pass along to the next state
          None.asInstanceOf[Option[String]]) // The next block expiration to use if not overwritten
      ) {
        case ((processed, userBlocks, blockExpiration), state) =>
          val (effectiveBlocks, effectiveExpiration) =
            // if the worked state was generated by a block change
            // update block values, else use previous block values
            if (state.causedByEventType == "alterblocks") {
              (state.userBlocksHistorical, state.causedByBlockExpiration)
            } else {
              (userBlocks, blockExpiration)
            }
          // If block expiration is defined and is a timestamp ("indefinite"
          // is not part of that case), check that it happened before the end of
          // the currently worked state, update currently worked state with
          // userBlocks and add a new state for the unblock. reset userBlocks
          // and expiration to undefined for the next state in fold.
          val effectiveExpirationTimestamp = TimestampHelpers.makeMediawikiTimestamp(effectiveExpiration.orNull)
          if (effectiveExpirationTimestamp.isDefined &&
              effectiveExpirationTimestamp.get.before(presentTimestamp) &&
              (state.endTimestamp.isEmpty ||
                effectiveExpirationTimestamp.get.before(state.endTimestamp.get) ||
                effectiveExpirationTimestamp.get.equals(state.endTimestamp.get)
                )) {
            (
                processed :+ state.copy(
                    userBlocksHistorical = effectiveBlocks,
                    endTimestamp = effectiveExpirationTimestamp
                ) :+ state.copy(
                    startTimestamp = effectiveExpirationTimestamp,
                    userBlocksHistorical = Seq.empty[String],
                    causedByEventType = "alterblocks",
                    causedByUserId = None,
                    causedByBlockExpiration = None,
                    inferredFrom = Some("unblock")
                ),
                Seq.empty[String],
                None
            )
          // Update currently worked state with userBlock and pass userBlocks
          // and expiration to next state in fold.
          } else {
            (
                processed :+ state.copy(userBlocksHistorical = effectiveBlocks),
                effectiveBlocks,
                effectiveExpiration
            )
          }
      }
      ._1
    // Propagate mostRecentBlocks from right to left.
    val mostRecentBlocks = propagated.last.userBlocksHistorical
    propagated.map(s => s.copy(userBlocks = mostRecentBlocks))
  }

  /**
    * Groups states by user id, order them by timestamp in each group, and apply
    * - [[propagateUserBlocks]]
    * - [[propagateUserGroups]]
    * - [[propagateUserRegistrationAndCreatedBy]]
    * - [[updateAnonymousAndBotByName]]
    *
    * @param states The states sequence to work
    * @return The updated states
    */
  def propagateStates(states: Seq[UserState]): Seq[UserState] = {
    states
      .groupBy(_.userId)
      .flatMap {
        case (userId, userStates) =>
          val sortedStates = userStates.toList.sortWith {
            case (a, b) =>
              a.startTimestamp.isEmpty || (
                  b.startTimestamp.isDefined &&
                    a.startTimestamp.get.before(b.startTimestamp.get)
              )
          }
          updateAnonymousAndBotByName(
            propagateUserRegistrationAndCreatedBy(
              propagateUserGroups(
                propagateUserBlocks(sortedStates)
              )
            )
          )
      }
      .toSeq
  }


  /**
    * This function rebuilds user history for a single
    * partition given as events and states iterators.
    *
    * It does so by
    *  - building an initial [[ProcessingStatus]] from the given
    *    states (every state being a potential one)
    *  - sorting events by timestamp in descending orders
    *  - Iterate through the sorted events and join them to the states.
    *    Done using foldLeft with a [[ProcessingStatus]] as aggregated value.
    *    Depending on the event type (rename, altergroup...), status is
    *    updated (new potential state created, current mark as complete ...)
    *  - Update still-to-join states to known states when there is no more events
    *  - Reiterate over resulting states in time-ascending order to propagate
    *    values that can't be propagated backward.
    *
    * @param events The user events iterable
    * @param states The user states iterable
    * @return The reconstructed user state history (for the given partition)
    *         and errors
    */
  def processSubgraph(
      events: Iterable[UserEvent],
      states: Iterable[UserState]
  ): (
      Seq[UserState], // processed states
      Seq[UserEvent]  // unmatched events
  ) = {

    val sortedEvents = events.toList.sortWith {
      case (a, b) => a.timestamp.after(b.timestamp)
    }
    val (fStates: Seq[UserState], unmatchedEvents: Seq[UserEvent]) = {
      if (sortedEvents.isEmpty) {
        val finalStates = states.map(s => s.copy(
            startTimestamp = s.userRegistrationTimestamp,
            causedByEventType = "create",
            causedByUserId = None,
            inferredFrom = Some("unclosed")
          )).toSeq
        (finalStates, Seq.empty[UserEvent])
      } else {
        val statesMap = states.map(s => (s.wikiDb, s.userNameHistorical) -> s).toMap
        val initialStatus = new ProcessingStatus(
          todayToCurrent = Map.empty[UserHistoryBuilder.KEY, UserHistoryBuilder.KEY],
          currentToToday = Map.empty[UserHistoryBuilder.KEY, UserHistoryBuilder.KEY],
          potentialStates = statesMap,
          knownStates = Seq.empty[UserState],
          unmatchedEvents = Seq.empty[UserEvent]
        )
        val finalStatus = sortedEvents.foldLeft(initialStatus)(processEvent)
        val finalStates = {
          // Flush the states that were left in the dictionary.
          finalStatus.potentialStates.values.map(
            s =>
              s.copy(
                startTimestamp = s.userRegistrationTimestamp,
                causedByEventType = "create",
                causedByUserId = None,
                inferredFrom = Some("unclosed")
              )).toSeq ++ finalStatus.knownStates
        }
        (finalStates, finalStatus.unmatchedEvents)
      }
    }
    (propagateStates(fStates), unmatchedEvents)
  }


  /**
    * This function is the entry point of this class.
    * It first partitions events and states RDDs using
    * [[org.wikimedia.analytics.refinery.spark.utils.SubgraphPartitioner]],
    * then applies its [[processSubgraph]] method to every partition, and finally returns joined
    * states results, along with error events.
    *
    * @param events The user events RDD to be used for reconstruction
    * @param states The initial user states RDD to be used for reconstruction
    * @return The reconstructed user states history and errors or their count.
    */
  def run(
    events: RDD[UserEvent],
    states: RDD[UserState]
  ): (
    RDD[UserState],
    RDD[UserEvent]
  ) = {
    log.info(s"User history building jobs starting")

    val partitioner = new SubgraphPartitioner[
      UserHistoryBuilder.KEY,
      UserHistoryBuilder.STATS_GROUP,
      UserEvent,
      UserState
    ](
      spark,
      UserHistoryBuilder.UserRowKeyFormat,
      statsAccumulator
    )

    val subgraphs = partitioner.run(events, states)

    log.info(s"Processing partitioned user histories")
    val processedSubgraphs = subgraphs.map(g => processSubgraph(g._1, g._2))
    val processedStates = processedSubgraphs.flatMap(_._1)
    val matchingErrors = processedSubgraphs.flatMap(_._2)

    log.info(s"User history building jobs done")

    (processedStates, matchingErrors)
  }
}


/**
  * This companion object defines a shortening for user reconstruction key type,
  * and the needed conversions of this type between RDD and dataframe to
  * be used in graph partitioning.
  */
object UserHistoryBuilder extends Serializable{

  import org.apache.spark.sql.Row
  import org.apache.spark.sql.types.{StringType, StructField, StructType}
  import org.wikimedia.analytics.refinery.spark.utils.RowKeyFormat

  val METRIC_SUBGRAPH_PARTITIONS = "users.subgraphPartitions.count"

  type KEY = (String, String)
  type STATS_GROUP = String

  object UserRowKeyFormat extends RowKeyFormat[KEY, STATS_GROUP] with Serializable {
    val struct = StructType(Seq(
      StructField("wiki_db", StringType, nullable = false),
      StructField("username", StringType, nullable = false)
    ))
    def toRow(k: KEY): Row = Row.fromTuple(k)
    def toKey(r: Row): KEY = (r.getString(0), r.getString(1))
    def statsGroup(k: KEY): STATS_GROUP = s"${k._1}.$METRIC_SUBGRAPH_PARTITIONS"
  }
}
