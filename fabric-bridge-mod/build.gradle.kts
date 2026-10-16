plugins {
    id("java")
}

group = "io.velocraft.mod"
version = "0.1.0"

base {
    archivesName.set("velocraft-bridge-mod")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(17)
}
