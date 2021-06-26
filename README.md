# red-alert

[![Maven Package](https://github.com/ashr123/red-alert/actions/workflows/maven-publish.yml/badge.svg?branch=1.0.0)](https://github.com/ashr123/red-alert/actions/workflows/maven-publish.yml)

## Requirements

1. Needs to be run from current working directory for being able to
   find [red-alert-settings.json](red-alert-settings.json).
2. [red-alert-settings.json](red-alert-settings.json) needs to be in the same directory as the `jar` file.
3. JDK 16 (or newer)
4. Must have an Israeli IP address.

## Capabilities

1. Display alerts as the Home Front Command produces them.
2. Make alert sound if the alert contains areas of interest defined
   in [red-alert-settings.json](red-alert-settings.json).
3. Supports all official language:
   - Hebrew (code `HE`)
   - English (code `EN`) (Need to be tested)
   - Russian (code `RU`) (Need to be tested)
   - Arabic (code `AR`) (Need to be tested)

   Legal districts can be found in [districts.json](districts.json) or
   in [LanguageUtil.java](src/main/java/il/co/LanguageUtil.java) by language code

## Demonstration

![demo](pic.png "Demo")