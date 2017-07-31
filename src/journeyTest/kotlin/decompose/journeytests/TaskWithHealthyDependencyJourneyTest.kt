package decompose.journeytests

import com.natpryce.hamkrest.assertion.assert
import com.natpryce.hamkrest.containsSubstring
import com.natpryce.hamkrest.equalTo
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.given
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on

object TaskWithHealthyDependencyJourneyTest : Spek({
    given("a task with a healthy dependency") {
        val runner = ApplicationRunner("task-with-healthy-dependency", listOf("run", "decompose.yml", "the-task"))

        on("running that task") {
            val result = runner.run()

            it("displays the output from that task") {
                assert.that(result.output, containsSubstring("Status code for request: 200"))
            }

            it("returns the exit code from that task") {
                assert.that(result.exitCode, equalTo(0))
            }
        }
    }
})
