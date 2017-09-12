/*
   Copyright 2017 Charles Korn.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package batect.ui

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on

object EventLoggerProviderSpec : Spek({
    describe("an event logger provider") {
        val simpleEventLogger = mock<SimpleEventLogger>()
        val fancyEventLogger = mock<FancyEventLogger>()

        on("when a TTY is connected to STDIN") {
            val consoleInfo = mock<ConsoleInfo> {
                on { stdinIsTTY } doReturn true
            }

            val provider = EventLoggerProvider(simpleEventLogger, fancyEventLogger, consoleInfo)
            val logger = provider.getEventLogger()

            it("returns the fancy event logger") {
                assertThat(logger, equalTo<EventLogger>(fancyEventLogger))
            }
        }

        on("when a TTY is not connected to STDIN") {
            val consoleInfo = mock<ConsoleInfo> {
                on { stdinIsTTY } doReturn false
            }

            val provider = EventLoggerProvider(simpleEventLogger, fancyEventLogger, consoleInfo)
            val logger = provider.getEventLogger()

            it("returns the simple event logger") {
                assertThat(logger, equalTo<EventLogger>(simpleEventLogger))
            }
        }
    }
})
