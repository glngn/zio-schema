import sbt._
import Keys._

import sbtcrossproject.CrossPlugin.autoImport.crossProjectPlatform
import sbtbuildinfo._
import BuildInfoKeys._
import scalafix.sbt.ScalafixPlugin.autoImport._

object BuildHelper {

  val Scala213: String = "2.13.10"

  val zioVersion               = "1.0.10"
  val zioJsonVersion           = "0.2.0"
  val zioPreludeVersion        = "1.0.0-RC8"
  val zioOpticsVersion         = "0.1.0"
  val silencerVersion          = "1.7.11"
  val avroVersion              = "1.11.0"
  val zioConstraintlessVersion = "0.3.1"

  private val testDeps = Seq(
    "dev.zio" %% "zio-test"     % zioVersion % "test",
    "dev.zio" %% "zio-test-sbt" % zioVersion % "test"
  )

  def macroDefinitionSettings = Seq(
    scalacOptions += "-language:experimental.macros",
    libraryDependencies ++= {
        Seq(
          "org.scala-lang" % "scala-reflect"  % scalaVersion.value % "provided",
          "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided"
        )
    }
  )

  private def compileOnlyDeps(scalaVersion: String): Seq[ModuleID] =
    (
      CrossVersion.partialVersion(scalaVersion) match {
        case Some((2, x)) =>
          Seq(
            compilerPlugin(("org.typelevel" %% "kind-projector" % "0.13.2").cross(CrossVersion.full))
          )
        case _ => Seq.empty
      }
    ) ++ (
      CrossVersion.partialVersion(scalaVersion) match {
        case Some((2, x)) if x <= 12 =>
          Seq(
            compilerPlugin(("org.scalamacros" % "paradise" % "2.1.1").cross(CrossVersion.full))
          )
        case _ => Seq.empty
      }
    )

  private def compilerOptions(scalaVersion: String, optimize: Boolean) = {
    val stdOptions = Seq(
      "-deprecation",
      "-encoding",
      "UTF-8",
      "-feature",
      "-unchecked",
      "-language:existentials"
    ) ++ {
      if (sys.env.contains("CI")) {
        Seq("-Xfatal-warnings")
      } else {
        Seq()
      }
    }

    val std2xOptions = Seq(
      "-language:higherKinds",
      "-explaintypes",
      "-Yrangepos",
      "-Xlint:_,-missing-interpolator,-type-parameter-shadow,-infer-any",
      "-Ypatmat-exhaust-depth",
      "40",
      "-Ywarn-numeric-widen",
      "-Ywarn-value-discard",
      "-Xsource:3.0"
    )

    val optimizerOptions =
      if (optimize)
        Seq(
          "-opt:l:inline"
        )
      else Seq.empty

    val extraOptions = CrossVersion.partialVersion(scalaVersion) match {
      case Some((3, _)) =>
        Seq(
          "-language:implicitConversions",
          "-Xignore-scala2-macros",
          "-Ykind-projector"
        )
      case Some((2, 13)) =>
        Seq(
          "-opt-warnings",
          "-Ywarn-extra-implicit",
          "-Ywarn-unused",
          "-Ymacro-annotations"
        ) ++ std2xOptions ++ optimizerOptions
      case Some((2, 12)) =>
        Seq(
          "-Ypartial-unification",
          "-opt-warnings",
          "-Ywarn-extra-implicit",
          "-Ywarn-unused",
          "-Yno-adapted-args",
          "-Ywarn-inaccessible",
          "-Ywarn-nullary-override",
          "-Ywarn-nullary-unit"
        ) ++ std2xOptions ++ optimizerOptions
      case _ => Seq.empty
    }

    stdOptions ++ extraOptions
  }

  def platformSpecificSources(platform: String, conf: String, baseDirectory: File)(versions: String*): Seq[File] =
    for {
      platform <- List("shared", platform)
      version  <- "scala" :: versions.toList.map("scala-" + _)
      result   = baseDirectory.getParentFile / platform.toLowerCase / "src" / conf / version
      if result.exists
    } yield result

  def crossPlatformSources(scalaVer: String, platform: String, conf: String, baseDir: File): Seq[sbt.File] = {
    val versions = CrossVersion.partialVersion(scalaVer) match {
      case Some((2, 11)) =>
        List("2.11", "2.11+", "2.11-2.12", "2.x")
      case Some((2, 12)) =>
        List("2.12", "2.11+", "2.12+", "2.11-2.12", "2.12-2.13", "2.x")
      case Some((2, 13)) =>
        List("2.13", "2.11+", "2.12+", "2.13+", "2.12-2.13", "2.x")
      case _ =>
        List()
    }
    platformSpecificSources(platform, conf, baseDir)(versions: _*)
  }

  lazy val crossProjectSettings = Seq(
    Compile / unmanagedSourceDirectories ++= {
      crossPlatformSources(
        scalaVersion.value,
        crossProjectPlatform.value.identifier,
        "main",
        baseDirectory.value
      )
    },
    Test / unmanagedSourceDirectories ++= {
      crossPlatformSources(
        scalaVersion.value,
        crossProjectPlatform.value.identifier,
        "test",
        baseDirectory.value
      )
    }
  )

  def buildInfoSettings(packageName: String) = Seq(
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, isSnapshot),
    buildInfoPackage := packageName
  )

  def stdSettings(prjName: String) =
    Seq(
      name := s"$prjName",
      crossScalaVersions := Seq(Scala213),
      ThisBuild / scalaVersion := Scala213, //crossScalaVersions.value.head, //Scala3,
      scalacOptions := compilerOptions(scalaVersion.value, optimize = !isSnapshot.value),
      libraryDependencies ++= compileOnlyDeps(scalaVersion.value) ++ testDeps,
      ThisBuild / semanticdbEnabled := true,
      ThisBuild / semanticdbOptions += "-P:semanticdb:synthetics:on",
      ThisBuild / semanticdbVersion := scalafixSemanticdb.revision,
      ThisBuild / scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value),
      ThisBuild / scalafixDependencies ++= List(
        "com.github.liancheng" %% "organize-imports" % "0.6.0",
        "com.github.vovapolu"  %% "scaluzzi"         % "0.1.21"
      ),
      Test / parallelExecution := !sys.env.contains("CI"),
      incOptions ~= (_.withLogRecompileOnMacro(true)),
      autoAPIMappings := true,
      testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))
    )
}
