/*
   Copyright 2017-2020 Charles Korn.

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

package batect.buildtools

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

class KotlinPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.repositories {
            mavenCentral()
            jcenter()
        }

        applyKotlin(project)
        applySpotless(project)
        configureTesting(project)
        applyJacoco(project)
    }

    private void applyKotlin(Project project) {
        project.plugins.apply('org.jetbrains.kotlin.jvm')
        project.plugins.apply('org.jetbrains.kotlin.plugin.serialization')

        project.sourceCompatibility = JavaVersion.VERSION_1_8
        project.targetCompatibility = JavaVersion.VERSION_1_8

        project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
            kotlinOptions {
                jvmTarget = "1.8"
                freeCompilerArgs = ["-progressive"]
            }
        }
    }

    private void applySpotless(Project project) {
        project.plugins.apply('com.diffplug.gradle.spotless')

        def kotlinLicenseHeader = "/*${project.rootProject.licenseText}*/\n\n"
        project.extensions.add("kotlinLicenseHeader", kotlinLicenseHeader)

        project.spotless {
            kotlin {
                ktlint("0.30.0")

                licenseHeader kotlinLicenseHeader

                trimTrailingWhitespace()
                indentWithSpaces()
                endWithNewline()
            }
        }

        project.afterEvaluate {
            project.tasks.named('spotlessKotlinCheck') {
                mustRunAfter 'test'
            }

            project.tasks.named('spotlessKotlin') {
                mustRunAfter 'test'
            }
        }
    }

    private void configureTesting(Project project) {
        configureCommonTesting(project)
        configureUnitTesting(project)

        def checkUnitTestLayout = project.tasks.register('checkUnitTestLayout', UnitTestLayoutCheck.class) {
            mustRunAfter 'test'
        }
    }

    private void configureCommonTesting(Project project) {
        project.sourceSets {
            testCommon
        }

        project.dependencies {
            testCommonImplementation 'org.jetbrains.kotlin:kotlin-stdlib-jdk8'
            testCommonImplementation 'com.natpryce:hamkrest:1.7.0.3'
            testCommonImplementation "org.spekframework.spek2:spek-dsl-jvm:2.0.11"
            testCommonRuntimeOnly "org.spekframework.spek2:spek-runner-junit5:2.0.11"
            testCommonRuntimeOnly 'org.junit.platform:junit-platform-engine:1.6.2'
        }

        project.tasks.withType(Test).configureEach {
            if (JavaVersion.current() >= JavaVersion.VERSION_1_9) {
                jvmArgs '-Xshare:off', '--add-opens', 'java.base/sun.nio.ch=ALL-UNNAMED', '--add-opens', 'java.base/java.io=ALL-UNNAMED'
            } else {
                jvmArgs '-Xshare:off'
            }

            useJUnitPlatform {
                includeEngines 'spek2'
            }

            testLogging {
                events "failed"
                events "skipped"
                events "standard_out"
                events "standard_error"

                showExceptions true
                showStackTraces true
                showCauses true
                exceptionFormat "full"
            }
        }
    }

    private void configureUnitTesting(Project project) {
        project.sourceSets {
            test {
                kotlin {
                    srcDirs = ['src/unitTest/kotlin']
                }

                resources {
                    srcDirs = ['src/unitTest/resources']
                }

                compileClasspath += testCommon.output
                runtimeClasspath += testCommon.output
            }
        }

        project.configurations {
            testImplementation.extendsFrom testCommonImplementation
            testRuntimeOnly.extendsFrom testCommonRuntimeOnly
        }

        project.dependencies {
            // We don't use mockito directly, but mockito-kotlin does refer to it, so override it to get the latest version.
            testImplementation 'org.mockito:mockito-core:3.3.3'
            testImplementation 'com.nhaarman.mockitokotlin2:mockito-kotlin:2.2.0'
        }
    }

    private void applyJacoco(Project project) {
        project.plugins.apply('jacoco')

        project.jacoco {
            toolVersion = '0.8.5'
        }

        project.jacocoTestReport {
            reports {
                xml.enabled true
            }
        }

        def generateCoverage = System.getProperty("generateCoverage", "false")

        project.tasks.withType(Test).configureEach { task ->
            task.jacoco {
                enabled = generateCoverage == "true" && task.name == "test"
            }
        }
    }
}
