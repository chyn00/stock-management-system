plugins {
    java
    id("org.springframework.boot") version "4.1.3"
}

group = "com.example"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.3"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
