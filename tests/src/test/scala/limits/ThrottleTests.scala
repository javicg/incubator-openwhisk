/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package limits

import java.time.Instant

import scala.collection.parallel.immutable.ParSeq
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Try

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils
import common.TestUtils._
import common.WhiskProperties
import common.Wsk
import common.WskActorSystem
import common.WskProps
import common.WskTestHelpers
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.http.Messages._
import whisk.utils.ExecutionContextFactory
import org.scalatest.BeforeAndAfterAll
import common.RunWskAdminCmd

@RunWith(classOf[JUnitRunner])
class ThrottleTests
    extends FlatSpec
    with TestHelpers
    with WskTestHelpers
    with WskActorSystem
    with ScalaFutures
    with Matchers {

    // use an infinite thread pool so that activations do not wait to send the activation requests
    override implicit val executionContext = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()

    implicit val testConfig = PatienceConfig(5.minutes)
    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val defaultAction = Some(TestUtils.getTestActionFilename("hello.js"))

    val throttleWindow = 1.minute

    val maximumInvokesPerMinute = getLimit("defaultLimits.actions.invokes.perMinute", "limits.actions.invokes.perMinute")
    val maximumFiringsPerMinute = getLimit("defaultLimits.triggers.fires.perMinute", "limits.triggers.fires.perMinute")
    val maximumConcurrentInvokes = getLimit("defaultLimits.actions.invokes.concurrent", "limits.actions.invokes.concurrent")

    println(s"maximumInvokesPerMinute  = $maximumInvokesPerMinute")
    println(s"maximumFiringsPerMinute  = $maximumFiringsPerMinute")
    println(s"maximumConcurrentInvokes = $maximumConcurrentInvokes")

    /*
     * Retrieve a numeric limit for the key from the property set.  If the overrideKey is present, use that.
     */
    def getLimit(key: String, overrideKey: String) = Try {
        WhiskProperties.getProperty(overrideKey).toInt
    } getOrElse {
        WhiskProperties.getProperty(key).toInt
    }

    /**
     * Extracts the number of throttled results from a sequence of <code>RunResult</code>
     *
     * @param results the sequence of results
     * @param message the message to determine the type of throttling
     */
    def throttledActivations(results: List[RunResult], message: String) = {
        val count = results.count { result =>
            result.exitCode == TestUtils.THROTTLED && result.stderr.contains(message)
        }
        println(s"number of throttled activations: $count out of ${results.length}")
        count
    }

    /**
     * Waits until all successful activations are finished. Used to prevent the testcases from
     * leaking activations.
     *
     * @param results the sequence of results from invocations or firings
     */
    def waitForActivations(results: ParSeq[RunResult]) = results.foreach { result =>
        if (result.exitCode == SUCCESS_EXIT) {
            withActivation(wsk.activation, result, totalWait = 5.minutes)(identity)
        }
    }

    /**
     * Settles throttles of 1 minute. Waits up to 1 minute depending on the time already waited.
     *
     * @param waitedAlready the time already gone after the last invoke or fire
     */
    def settleThrottles(waitedAlready: FiniteDuration) = {
        val timeToWait = (throttleWindow - waitedAlready).max(Duration.Zero)
        println(s"Waiting for ${timeToWait.toSeconds} seconds, already waited for ${waitedAlready.toSeconds} seconds")
        Thread.sleep(timeToWait.toMillis)
    }

    /**
     * Calculates the <code>Duration</code> between two <code>Instant</code>
     *
     * @param start the Instant something started
     * @param end the Instant something ended
     */
    def durationBetween(start: Instant, end: Instant) = Duration.fromNanos(java.time.Duration.between(start, end).toNanos)

    /**
     * Invokes the given action up to 'count' times until one of the invokes is throttled.
     *
     * @param count maximum invocations to make
     */
    def untilThrottled(count: Int, retries: Int = 3)(run: () => RunResult): List[RunResult] = {
        val p = Promise[Unit]

        val results = List.fill(count)(Future {
            if (!p.isCompleted) {
                val rr = run()
                if (rr.exitCode != SUCCESS_EXIT) {
                    println(s"exitCode = ${rr.exitCode}   stderr = ${rr.stderr.trim}")
                }
                if (rr.exitCode == THROTTLED) {
                    p.trySuccess(())
                }
                Some(rr)
            } else {
                println("already throttled, skipping additional runs")
                None
            }
        })

        val finished = Future.sequence(results).futureValue.flatten
        // some activations may need to be retried
        val failed = finished filter {
            rr => rr.exitCode != SUCCESS_EXIT && rr.exitCode != THROTTLED
        }

        println(s"Executed ${finished.length} requests, maximum was $count, need to retry ${failed.length} (retries left: $retries)")
        if (failed.isEmpty || retries <= 0) {
            finished
        } else {
            finished ++ untilThrottled(failed.length, retries - 1)(run)
        }
    }

    behavior of "Throttles"

    it should "throttle multiple activations of one action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "checkPerMinuteActionThrottle"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, defaultAction)
            }

            // Three things to be careful of:
            //   1) We do not know the minute boundary so we perform twice max so that it will trigger no matter where they fall
            //   2) We cannot issue too quickly or else the concurrency throttle will be triggered
            //   3) In the worst case, we do about almost the limit in the first min and just exceed the limit in the second min.
            val totalInvokes = 2 * maximumInvokesPerMinute
            val numGroups = (totalInvokes / maximumConcurrentInvokes) + 1
            val invokesPerGroup = (totalInvokes / numGroups) + 1
            val interGroupSleep = 5.seconds
            val results = (1 to numGroups).map { i =>
                if (i != 1) { Thread.sleep(interGroupSleep.toMillis) }
                untilThrottled(invokesPerGroup) { () =>
                    wsk.action.invoke(name, Map("payload" -> "testWord".toJson), expectedExitCode = DONTCARE_EXIT)
                }
            }.flatten.toList
            val afterInvokes = Instant.now

            try {
                val throttledCount = throttledActivations(results, tooManyRequests)
                throttledCount should be > 0
            } finally {
                val alreadyWaited = durationBetween(afterInvokes, Instant.now)
                settleThrottles(alreadyWaited)
                println("clearing activations")
            }
            // wait for the activations last, if these fail, the throttle should be settled
            // and this gives the activations time to complete and may avoid unnecessarily polling
            println("waiting for activations to complete")
            waitForActivations(results.par)
    }

    it should "throttle multiple activations of one trigger" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "checkPerMinuteTriggerThrottle"
            assetHelper.withCleaner(wsk.trigger, name) {
                (trigger, _) => trigger.create(name)
            }

            // invokes per minute * 2 because the current minute could advance which resets the throttle
            val results = untilThrottled(maximumFiringsPerMinute * 2 + 1) { () =>
                wsk.trigger.fire(name, Map("payload" -> "testWord".toJson), expectedExitCode = DONTCARE_EXIT)
            }
            val afterFirings = Instant.now

            try {
                val throttledCount = throttledActivations(results, tooManyRequests)
                throttledCount should be > 0
            } finally {
                // no need to wait for activations of triggers since they consume no resources
                // (because there is no rule attached in this test)
                val alreadyWaited = durationBetween(afterFirings, Instant.now)
                settleThrottles(alreadyWaited)
            }
    }

    it should "throttle 'concurrent' activations of one action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "checkConcurrentActionThrottle"
            assetHelper.withCleaner(wsk.action, name) {
                val timeoutAction = Some(TestUtils.getTestActionFilename("timeout.js"))
                (action, _) => action.create(name, timeoutAction)
            }

            // The sleep is necessary as the load balancer currently has a latency before recognizing concurency.
            val sleep = 15.seconds
            val slowInvokes = maximumConcurrentInvokes
            val fastInvokes = 2
            val fastInvokeDuration = 4.seconds
            val slowInvokeDuration = sleep + fastInvokeDuration

            // These invokes will stay active long enough that all are issued and load balancer has recognized concurrency.
            val startSlowInvokes = Instant.now
            val slowResults = untilThrottled(slowInvokes) { () =>
                wsk.action.invoke(name, Map("payload" -> slowInvokeDuration.toMillis.toJson), expectedExitCode = DONTCARE_EXIT)
            }
            val afterSlowInvokes = Instant.now
            val slowIssueDuration = durationBetween(startSlowInvokes, afterSlowInvokes)
            println(s"$slowInvokes slow invokes (dur = ${slowInvokeDuration.toSeconds} sec) took ${slowIssueDuration.toSeconds} seconds to issue")

            // Sleep to let the background thread get the newest values (refreshes every 2 seconds)
            println(s"Sleeping for ${sleep.toSeconds} sec")
            Thread.sleep(sleep.toMillis)

            // These fast invokes will trigger the concurrency-based throttling.
            val startFastInvokes = Instant.now
            val fastResults = untilThrottled(fastInvokes) { () =>
                wsk.action.invoke(name, Map("payload" -> slowInvokeDuration.toMillis.toJson), expectedExitCode = DONTCARE_EXIT)
            }
            val afterFastInvokes = Instant.now
            val fastIssueDuration = durationBetween(afterFastInvokes, startFastInvokes)
            println(s"$fastInvokes fast invokes (dur = ${fastInvokeDuration.toSeconds} sec) took ${fastIssueDuration.toSeconds} seconds to issue")

            val combinedResults = slowResults ++ fastResults
            try {
                val throttledCount = throttledActivations(combinedResults, tooManyConcurrentRequests)
                throttledCount should be > 0
            } finally {
                val alreadyWaited = durationBetween(afterSlowInvokes, Instant.now)
                settleThrottles(alreadyWaited)
                println("clearing activations")
            }
            // wait for the activations last, giving the activations time to complete and
            // may avoid unnecessarily polling; if these fail, the throttle may not be settled
            println("waiting for activations to complete")
            waitForActivations(combinedResults.par)
    }
}

@RunWith(classOf[JUnitRunner])
class NamespaceSpecificThrottleTests
    extends FlatSpec
    with TestHelpers
    with WskTestHelpers
    with Matchers
    with BeforeAndAfterAll {

    val wskadmin = new RunWskAdminCmd {}
    val wsk = new Wsk

    val defaultAction = Some(TestUtils.getTestActionFilename("hello.js"))

    // Create a subject with rate limits == 0
    val zeroProps = getAdditionalTestSubject("zeroSubject")
    wskadmin.cli(Seq("limits", "set", zeroProps.namespace, "--invocationsPerMinute", "0", "--firesPerMinute", "0", "--concurrentInvocations", "0"))

    // Create a subject where only the concurrency limit is set to 0
    val zeroConcProps = getAdditionalTestSubject("zeroConcSubject")
    wskadmin.cli(Seq("limits", "set", zeroConcProps.namespace, "--concurrentInvocations", "0"))

    // Create a subject where the rate limits are set to 1
    val oneProps = getAdditionalTestSubject("oneSubject")
    wskadmin.cli(Seq("limits", "set", oneProps.namespace, "--invocationsPerMinute", "1", "--firesPerMinute", "1"))

    override def afterAll() = {
        disposeAdditionalTestSubject(zeroProps.namespace)
        disposeAdditionalTestSubject(zeroConcProps.namespace)
        disposeAdditionalTestSubject(oneProps.namespace)
    }

    behavior of "Namespace-specific throttles"

    it should "respect overridden rate-throttles of 0" in withAssetCleaner(zeroProps) {
        (wp, assetHelper) =>
            implicit val props = wp
            val triggerName = "zeroTrigger"
            val actionName = "zeroAction"

            assetHelper.withCleaner(wsk.action, actionName) {
                (action, _) => action.create(actionName, defaultAction)
            }
            assetHelper.withCleaner(wsk.trigger, triggerName) {
                (trigger, _) => trigger.create(triggerName)
            }

            wsk.action.invoke(actionName, expectedExitCode = TestUtils.THROTTLED).stderr should include(tooManyRequests)
            wsk.trigger.fire(triggerName, expectedExitCode = TestUtils.THROTTLED).stderr should include(tooManyRequests)
    }

    it should "respect overridden rate-throttles of 1" in withAssetCleaner(oneProps) {
        (wp, assetHelper) =>
            implicit val props = wp
            val triggerName = "oneTrigger"
            val actionName = "oneAction"

            assetHelper.withCleaner(wsk.action, actionName) {
                (action, _) => action.create(actionName, defaultAction)
            }
            assetHelper.withCleaner(wsk.trigger, triggerName) {
                (trigger, _) => trigger.create(triggerName)
            }

            // One invoke should be allowed, the second one throttled
            wsk.action.invoke(actionName)
            wsk.action.invoke(actionName, expectedExitCode = TestUtils.THROTTLED).stderr should include(tooManyRequests)

            // One fire should be allowed, the second one throttled
            wsk.trigger.fire(triggerName)
            wsk.trigger.fire(triggerName, expectedExitCode = TestUtils.THROTTLED).stderr should include(tooManyRequests)
    }

    it should "respect overridden concurrent throttle of 0" in withAssetCleaner(zeroConcProps) {
        (wp, assetHelper) =>
            implicit val props = wp
            val actionName = "zeroConcurrentAction"

            assetHelper.withCleaner(wsk.action, actionName) {
                (action, _) => action.create(actionName, defaultAction)
            }

            wsk.action.invoke(actionName, expectedExitCode = TestUtils.THROTTLED).stderr should include(tooManyConcurrentRequests)
    }

}
