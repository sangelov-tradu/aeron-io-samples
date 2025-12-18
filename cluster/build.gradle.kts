/*
 * Copyright 2023 Adaptive Financial Consulting
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    id("java-application-conventions")
}

dependencies {
    implementation(libs.agrona)
    implementation(libs.aeron)
    implementation(libs.slf4j)
    implementation(libs.logback)
    implementation(project(":cluster-protocol"))
    testImplementation(libs.bundles.testing)

    // Temporary workaround for IntelliJ IDEA Kotlin coroutines debug agent
    // This is NOT needed for the application itself (pure Java)
    // Only added to prevent IntelliJ's Kotlin plugin from failing when running via IDE
    runtimeOnly("org.jetbrains.kotlin:kotlin-stdlib:1.9.20")
    runtimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

application {
    mainClass = "io.aeron.samples.ClusterApp"
}

tasks {
    register<Task>("runSingleNodeCluster") {
        group = "run"
        dependsOn("run")
    }
}
