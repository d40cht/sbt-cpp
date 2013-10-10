#!/bin/bash

set -e

rm -rf target

../../sbt "nativeBuildConfiguration Gcc_LinuxPC_Debug" compile test
../../sbt "nativeBuildConfiguration Gcc_LinuxPC_Release" compile test
#../../sbt "nativeBuildConfiguration Clang_LinuxPC_Debug" compile test
#../../sbt "nativeBuildConfiguration Clang_LinuxPC_Release" compile test

