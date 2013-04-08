#!/bin/bash
rm -rf ~/.sbt/staging && ../../sbt "build-environment Debug_Gcc_LinuxPC" clean test
