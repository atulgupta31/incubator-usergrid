/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one or more
 *  *  contributor license agreements.  The ASF licenses this file to You
 *  * under the Apache License, Version 2.0 (the "License"); you may not
 *  * use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.  For additional information regarding
 *  * copyright in this work, please see the NOTICE file in the top level
 *  * directory of this distribution.
 *
 */

package org.apache.usergrid.simulations

import io.gatling.core.Predef._
import io.gatling.core.scenario.Simulation
import org.apache.usergrid.helpers.Setup
import org.apache.usergrid.scenarios.NotificationScenarios
import org.apache.usergrid.settings.Settings
import scala.annotation.switch
import scala.concurrent.duration._

/**
 * Classy class class.
 */
class AppSimulation extends Simulation {
  println("Begin setup")
  Setup.setupOrg()
  Setup.setupApplication()
  Setup.setupNotifier()
  Setup.setupUsers()
  println("End Setup")

  setUp(
    NotificationScenarios.createScenario
      //inject all our users over the ramp time
//      .inject(constantUsersPerSec(Settings.maxPossibleUsers) during (Settings.rampTime))
      //ramp up to our max possible users over the ramp time.  We may not get there if we hit our max tps first
      .inject(rampUsers(Settings.maxPossibleUsers) over  (Settings.rampTime))
      //during the ramp time, try to hit our max TPS.  Then hold it for the duration of the test
      .throttle(reachRps(Settings.throttle) in (Settings.rampTime.seconds), holdFor(Settings.duration))
      .protocols(Settings.httpConf.acceptHeader("application/json"))
  )
}
