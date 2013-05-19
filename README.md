# sbt-cpp

A plugin for [sbt](http://www.scala-sbt.org/) to enable cross-platform native (c/c++) builds.

[![Build Status](https://travis-ci.org/d40cht/sbt-cpp.png)](https://travis-ci.org/d40cht/sbt-cpp)

## Introduction

* Sbt-cpp is a cross-platform native build system, constructed on top of [sbt](http://www.scala-sbt.org/): an existing and mature build system for Scala and Java. Sbt implements core build-system concepts in a way that is sufficiently generic that a large proportion of its functionality can be re-used in the context of native builds.
* Sbt itself is extremely thoughtfully designed, and uses the Scala language in all build directive files. As a result you have the power (and libraries) of an extensible build system backed by a mature multi-paradigm programming language (in common, for instance, with [Scons](http://www.scons.org/) which uses python for build directive files).
* Scala is statically typed, which means that sbt compiles your build directive files before running a build. This allows the tool to catch considerably more errors than is possible with a build tool based around a dynamically typed language (e.g. Python). This also means that a large class of problems with infrequently used targets are caught without the target having to be built/run.
* Scala runs on the Java virtual machine, defering many platform-specific problems in build construction (filesystem paths, running external processes etc) to the Java runtime.
* Sbt-cpp aims to stick as closely as possible to the principles and conventions of sbt, to make as much use of existing sbt infrastructure and documentation - hopefully benefiting both developers and users of the system and providing a more coherent experience.

Before getting started with sbt-cpp, it is worthwhile getting to grips with the basic concepts of sbt itself, outlined in some detail in the getting started section of the sbt website [here](http://www.scala-sbt.org/release/docs/Getting-Started/Welcome.html).

## Limitations

* Sbt-cpp is a very new project, started around March 2013.
* Whilst having the ambitions to match the functionality of an established contender such as CMake (and providing a much more powerful scripting language), it currently has one main developer.
* The aforementioned developer currently only has access to a limited number of platforms and compilers at the moment, being:
 1. Various Linux variants (on ARM and x86) with Gcc and Clang. Both native and cross-compiling.
 2. Windows 7 with Visual Studio Cygwin and Mingw.
* Currently sbt-cpp is auto-built using Travis CI, which whilst wonderful, only currently supports Linux builds for open-source projects.

Having said the above, sbt-cpp is currently in deployment in at least one commercial environment with a reasonably complex multi-platform codebase. Any work to extend the tool, or offers of more rich environments for autobuild would be very gratefully accepted.


## Sbt-cpp quickstart

* Out of the box, at the moment, sbt-cpp supports Gcc, Clang and Visual studio for Linux and Windows (although adding support for other platforms and compilers is straightforward).
* The 'hello world' of sbt-cpp can be found in samples/helloworld. This contains:
 1. source/main.cpp, containing the standard C++ hello world example:
 
     ```
        #include <iostream>

        int main( int argc, char** argv )
        {
            std::cout << "Hello world from: " << argv[1] << std::endl;
            
            return 0;
        }
     ```
 
 2. project/build.scala, containing the directives for a single executable project (clearly there are currently too many imports required and there should be consolidation):
 
     ```
        import sbt._
        import Keys._
        import org.seacourt.build._
        import NativeProject._

        object TestBuild extends NativeDefaultBuild
        {
            lazy val check = NativeProject( "helloworld", file("./"), settings=nativeExeSettings )
        }
     ```
     
 * To build a debug executable for Linux using Gcc, you would complete the following simple steps (from the root directory of the project):
  1. Execute 'sbt' from the command prompt to enter the build system shell.
  2. Execute 'native-build-configuration Gcc_LinuxPC_Debug' in the sbt shell to choose the appropriate target.
  3. Execute 'compile' from the shell to build.
  4. Execute 'run Bob' from the shell to run the program and see the line:
  
     <pre>Hello world from Bob</pre>
  
     appear on the console.
  
 * As well as support for building simple single-project executables (settings=nativeExeSettings), sbt-cpp currently has support for (at varying stages of development):
  + Static and shared libraries.
  + C and C++.
  + Explicit dependencies between projects.
  + Unit testing.
  + Out-of-source builds.  
  + Per-platform configuration of libraries and tool chains (similar to CMake platform checks).
  + Caching of configuration data (including via auto-generated header files).
  + Parallel builds.
  + Incremental builds.
  + Cross-compilation.
  
 * Support is intended for the following, as time and/or relevant hardware become available:
  + Additional platforms: Mac OSX, Android and iOS for starters.
  + Additional compilers: Intel compiler, Oracle Solaris studio etc.
  + IDE support (Visual studio, Eclipse CDT).
  + External build system support (Make etc).
  + Auto-detection of compilers and build environments.
  + Auto-detection and configuration of libraries (e.g. Boost) and tools (e.g. Python).
  + Documentation builds (e.g. Doxygen).
  + Installer/package generation.

## Detailed documentation

* To come.
* For now see [here](test/various/project/build.scala) for a more detailed example project showing some of the currently existing functionality.

## Per user/machine config overrides

* In addition to directives in scala for describing the build, sbt-cpp allows additional per-user overrides of standard configuration using the [Typesafe](http://typesafe.com/) [config](https://github.com/typesafehub/config) library.
* Per-build overrides can be placed in 'build.conf' and checked in to a particular project repo. Per-user/machine overrides can be placed in 'user.conf', overriding all other config.
* An example config for the simple default build can be found [here](src/main/resources/reference.conf) with an extract for Gcc on Linux below:

```
Gcc.LinuxPC
{
    compilerCommon =
    {
        toolPaths           = ["/usr/bin"]
        includePaths        = []
        libraryPaths        = []
        ccExe               = "gcc"
        cxxExe              = "g++"
        archiver            = "ar"
        linker              = "g++"
        
        ccFlags             = ["-DLINUX", "-DGCC"]
        cxxFlags            = ["-DLINUX", "-DGCC"]
        linkFlags           = []
    }
    
    Debug   = ${Gcc.LinuxPC.compilerCommon}
    Release = ${Gcc.LinuxPC.compilerCommon}
    
    Debug
    {
        ccFlags     = ${Gcc.LinuxPC.compilerCommon.ccFlags} ["-g", "-DDEBUG"]
        cxxFlags    = ${Gcc.LinuxPC.compilerCommon.cxxFlags} ["-g", "-DDEBUG"]
    }
    
    Release
    {
        ccFlags     = ${Gcc.LinuxPC.compilerCommon.ccFlags} ["-O2", "-DRELEASE"]
        cxxFlags    = ${Gcc.LinuxPC.compilerCommon.cxxFlags} ["-O2", "-DRELEASE"]
    }
}
```


## Native-specific keys

A list of the keys specific to sbt-cpp (for sbt aficionados) and their uses (some of which should probably be folded in to their sbt equivalents):

```
val exportedLibs = TaskKey[Seq[File]]("native-exported-libs", "All libraries exported by this project" )
val exportedLibDirectories = TaskKey[Seq[File]]("native-exported-lib-directories", "All library directories exported by this project" )
val exportedIncludeDirectories = TaskKey[Seq[File]]("native-exported-include-directories", "All include directories exported by this project" )

val rootBuildDirectory = TaskKey[File]("native-root-build-dir", "Build root directory (for the config, not the project)")
val projectBuildDirectory = TaskKey[File]("native-project-build-dir", "Build directory for this config and project")
val stateCacheDirectory = TaskKey[File]("native-state-cache-dir", "Build state cache directory")
val projectDirectory = TaskKey[File]("native-project-dir", "Project directory")
val sourceDirectories = TaskKey[Seq[File]]("native-source-dirs", "Source directories")
val includeDirectories = TaskKey[Seq[File]]("native-include-dirs", "Include directories")
val systemIncludeDirectories = TaskKey[Seq[File]]("native-system-include-dirs", "System include directories")
val linkDirectories = TaskKey[Seq[File]]("native-link-dirs", "Link directories")
val nativeLibraries = TaskKey[Seq[String]]("native-libraries", "All native library dependencies for this project")
val ccSourceFiles = TaskKey[Seq[File]]("native-cc-source-files", "All C source files for this project")
val cxxSourceFiles = TaskKey[Seq[File]]("native-cxx-source-files", "All C++ source files for this project")
val ccSourceFilesWithDeps = TaskKey[Seq[(File, Seq[File])]]("native-cc-source-files-with-deps", "All C source files with dependencies for this project")
val cxxSourceFilesWithDeps = TaskKey[Seq[(File, Seq[File])]]("native-cxx-source-files-with-deps", "All C++ source files with dependencies for this project")
val objectFiles = TaskKey[Seq[File]]("native-object-files", "All object files for this project" )
val archiveFiles = TaskKey[Seq[File]]("native-archive-files", "All archive files for this project, specified by full path" )
val nativeExe = TaskKey[File]("native-exe", "Executable built by this project (if appropriate)" )
val testExe = TaskKey[Option[File]]("native-test-exe", "Test executable built by this project (if appropriate)" )
val nativeRun = TaskKey[Unit]("native-run", "Perform a native run of this project" )
val testProject = TaskKey[Project]("native-test-project", "The test sub-project for this project")
val testExtraDependencies = TaskKey[Seq[File]]("native-test-extra-dependencies", "Extra file dependencies of the test (used to calculate when to re-run tests)")
val nativeTest = TaskKey[Option[(File, File)]]("native-test-run", "Run the native test, returning the files with stdout and stderr respectively")
val test = TaskKey[Unit]("test", "Run the test associated with this project")
val environmentVariables = TaskKey[Seq[(String, String)]]("native-env-vars", "Environment variables to be set for running programs and tests")
val cleanAll = TaskKey[Unit]("native-clean-all", "Clean the entire build directory")
val ccCompileFlags = TaskKey[Seq[String]]("native-cc-flags", "Native C compile flags")
val cxxCompileFlags = TaskKey[Seq[String]]("native-cxx-flags", "Native C++ compile flags")
val linkFlags = TaskKey[Seq[String]]("native-link-flags", "Native link flags")
```
