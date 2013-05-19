
# sbt-cpp

A plugin for [sbt](http://www.scala-sbt.org/) to enable cross-platform native (c/c++) builds.

[![Build Status](https://travis-ci.org/d40cht/sbt-cpp.png)](https://travis-ci.org/d40cht/sbt-cpp)

## Concepts

* Sbt-cpp is a cross-platform native build system, constructed on top of [sbt](http://www.scala-sbt.org/) an existing and mature build system for Scala and Java. Sbt implements core build-system concepts in a way that is sufficiently generic that a large proportion of its functionality can be re-used in the context of native builds.
* Sbt itself is extremely thoughtfully designed, and uses the Scala language in all build directive files. As a result you have the power (and libraries) of a mature and multi-paradigm programming language (in common, for instance, with [Scons](http://www.scons.org/) which uses python for build directive files).
* Scala is statically typed, which means that sbt compiles your build directive files before running a build. This allows the tool to catch considerably more errors than is possible with a build tool based around a dynamically typed language (e.g. Python). This also means that a large class of problems with infrequently used targets are caught without the target having to be built/run.
* Scala runs on the Java virtual machine, defering many platform-specific problems in build construction (filesystem paths, running external processes etc) to the Java runtime.
* Before getting started with sbt-cpp, it is worthwhile getting to grips with the basic concepts of sbt itself, outlined in some detail in the getting started section of the sbt website [here](http://www.scala-sbt.org/release/docs/Getting-Started/Welcome.html).

## Limitations

* Sbt-cpp is a very new project, started around March 2013.
* Whilst having the ambitions to match the functionality of an established contender such as CMake (and providing a much more powerful scripting language), it currently has one main developer.
* The aforementioned developer currently only has access to a limited number of platforms and compilers at the moment, being:
 1. Various Linux variants (on ARM and x86) with Gcc and Clang. Both native and cross-compiling.
 2. Windows (7) with Visual Studio Cygwin and Mingw.


## Sbt-cpp quickstart

* Out of the box, at the moment, sbt-cpp supports Gcc, Clang and Visual studio for Linux and Windows (although adding support for other platforms and compilers is straightforward).
* The 'hello world' of sbt-cpp can be found in samples/helloworld. This contains:
 1. source/main.cpp, containing the standard C++ hello world example:
 
     <code>
         #include <iostream>


        int main( int argc, char** argv )
        {
            std::cout << "Hello world from: " << argv[1] << std::endl;
            
            return 0;
        }
     </code>
 
 2. project/build.scala, containing the directives for a single executable project:
 
     <code>
        import sbt._
        import Keys._
        import org.seacourt.build._
        import NativeProject._

        object TestBuild extends NativeDefaultBuild
        {
            
            
            lazy val check = NativeProject( "helloworld", file("./"), settings=nativeExeSettings )
        }
     </code>
     
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

## Detailed documentation

To come. For now see [here](blob/master/test/various/project/build.scala) for a more detailed example project.
