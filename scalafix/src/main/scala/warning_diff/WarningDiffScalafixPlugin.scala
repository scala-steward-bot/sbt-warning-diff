package warning_diff

import java.io.File
import sbt.*
import sbt.Keys.*
import scalafix.sbt.ScalafixPlugin
import scalafix.sbt.ScalafixPlugin.autoImport.ScalafixConfig
import scalafix.sbt.ScalafixPlugin.autoImport.scalafixDependencies
import sjsonnew.BasicJsonProtocol.*
import sjsonnew.JsonReader
import warning_diff.JsonClassOps.*
import warning_diff.WarningDiffPlugin.autoImport.*

object WarningDiffScalafixPlugin extends AutoPlugin {
  override def requires: Plugins = WarningDiffPlugin && ScalafixPlugin

  override def trigger: PluginTrigger = allRequirements

  object autoImport {
    val warningsScalafixConfig = taskKey[FixInput.SubProject]("")
    val warningsScalafix = taskKey[Seq[FixOutput]]("")
    val warningsScalafixScalaVersion = settingKey[String]("")
  }

  import autoImport.*

  private def moduleIdToString(m: ModuleID): String = {
    def q(s: String): String = "\"" + s + "\""
    m.crossVersion match {
      case _: CrossVersion.Binary =>
        s"""${q(m.organization)} %% ${q(m.name)} % ${q(m.revision)}"""
      case _: CrossVersion.Full =>
        s"""${q(m.organization)} % ${q(m.name)} % ${q(m.revision)} cross CrossVersion.full"""
      case _ =>
        s"""${q(m.organization)} % ${q(m.name)} % ${q(m.revision)}"""
    }
  }

  private val subProjects: Def.Initialize[Task[List[ResolvedProject]]] = Def.task {
    val extracted = Project.extract(state.value)
    val currentBuildUri = extracted.currentRef.build
    extracted.structure.units
      .apply(currentBuildUri)
      .defined
      .values
      .filter(
        _.autoPlugins.contains(WarningDiffScalafixPlugin)
      )
      .toList
  }

  override def projectSettings: Seq[Def.Setting[?]] = Def.settings(
    WarningDiffPlugin.warningConfigs.map { x =>
      x / warningsScalafixConfig := {
        val dialect = scalaBinaryVersion.value match {
          case "2.10" =>
            Dialect.Scala210
          case "2.11" =>
            Dialect.Scala211
          case "2.12" =>
            if (scalacOptions.value.exists(_ startsWith "-Xsource:3")) {
              Dialect.Scala212Source3
            } else {
              Dialect.Scala212
            }
          case "2.13" =>
            if (scalacOptions.value.exists(_ startsWith "-Xsource:3")) {
              Dialect.Scala213Source3
            } else {
              Dialect.Scala213
            }
          case "3" =>
            Dialect.Scala3
          case _ =>
            Dialect.Scala213Source3
        }
        FixInput.SubProject(
          projectId = thisProject.value.id,
          sbtConfig = x.id,
          scalafixConfig = IO.read(
            _root_.scalafix.sbt.ScalafixPlugin.autoImport.scalafixConfig.value
              .getOrElse(
                file(".scalafix.conf")
              )
          ),
          sources = (x / unmanagedSources).value.filter(_.getName.endsWith(".scala")).map(_.getCanonicalPath),
          scalacOptions = (x / scalacOptions).value,
          scalaVersion = scalaVersion.value,
          dialect = dialect
        )
      }
    },
    ThisBuild / warningsScalafixScalaVersion := {
      (ThisBuild / scalaBinaryVersion).value match {
        case "2.12" =>
          _root_.scalafix.sbt.BuildInfo.scala212
        case _ =>
          _root_.scalafix.sbt.BuildInfo.scala213
      }
    },
    ThisBuild / warningsScalafix := Def.taskDyn {
      val values: Seq[FixInput.SubProject] = Def.taskDyn {
        subProjects.value.flatMap { p =>
          WarningDiffPlugin.warningConfigs.map { x =>
            LocalProject(p.id) / x / warningsScalafixConfig
          }
        }.join
      }.value

      val scalaV = (ThisBuild / warningsScalafixScalaVersion).value
      val s = state.value

      if (values.exists(_.sources.nonEmpty)) {
        Def.task {
          val launcher = sbtLauncher.value

          val scalafixProducts: Seq[File] = Def
            .taskDyn {
              subProjects.value
                .withFilter(p =>
                  s.getSetting(LocalProject(p.id) / scalaBinaryVersion).exists { v =>
                    val x = sbt.librarymanagement.CrossVersion.binaryScalaVersion(scalaV)

                    (v == x) || (
                      (x == "2.13") && (v == "3")
                    )
                  }
                )
                .map(p => LocalProject(p.id) / ScalafixConfig / products)
                .join
            }
            .value
            .flatten

          val deps = (ThisBuild / scalafixDependencies).value ++ Seq(
            "ch.epfl.scala" %% "scalafix-rules" % _root_.scalafix.sbt.BuildInfo.scalafixVersion cross CrossVersion.full,
            "com.github.xuwei-k" %% "warning-diff-scalafix" % WarningDiffBuildInfo.version
          )

          val forkOps = (warningsScalafix / forkOptions).value

          IO.withTemporaryDirectory { tmp =>
            scalafixProducts.withFilter(_.isFile).withFilter(_.getName.endsWith(".jar")).foreach { f =>
              IO.copyFile(f, tmp / "lib" / f.getName)
            }
            val outputJson = tmp / "output.json"
            val input = FixInput(
              projects = values,
              base = (LocalRootProject / baseDirectory).value.getCanonicalPath,
              output = outputJson.getCanonicalPath
            )

            val buildSbt = Seq[String](
              s"""scalaVersion := "${scalaV}" """,
              deps
                .map(moduleIdToString)
                .mkString("libraryDependencies ++= Seq(\n", ",\n", "\n)")
            ).mkString("\n\n")

            IO.write(tmp / "build.sbt", buildSbt)
            IO.write(tmp / "input.json", input.toJsonString)
            val exitCode = Fork.java.apply(
              forkOps.withWorkingDirectory(tmp),
              Seq(
                "-jar",
                launcher.getCanonicalPath,
                "runMain warning_diff.ScalafixWarning"
              )
            )
            assert(exitCode == 0, s"exit code = $exitCode")
            val unbuilder = new sjsonnew.Unbuilder(sjsonnew.support.scalajson.unsafe.Converter.facade)
            val json = sjsonnew.support.scalajson.unsafe.Parser.parseFromFile(outputJson).get
            implicitly[JsonReader[Seq[FixOutput]]].read(Some(json), unbuilder)
          }
        }
      } else {
        Def.task(Seq.empty[FixOutput])
      }
    }.value,
    WarningDiffPlugin.warningConfigs.flatMap { x =>
      Def.settings(
        (x / warnings) ++= {
          (ThisBuild / warningsScalafix).value
            .collect {
              case y if (y.projectId == thisProject.value.id) && (y.sbtConfig == x.id) =>
                y.warnings
            }
            .flatten
            .filter(_.position.sourcePath.nonEmpty)
            .distinct
        }
      )
    }
  )

  private[this] def getJarFiles(module: ModuleID): Def.Initialize[Task[Seq[File]]] = Def.task {
    dependencyResolution.value
      .retrieve(
        dependencyId = module,
        scalaModuleInfo = scalaModuleInfo.value,
        retrieveDirectory = csrCacheDirectory.value,
        log = streams.value.log
      )
      .left
      .map(e => throw e.resolveException)
      .merge
      .distinct
  }

  private[this] def sbtLauncher: Def.Initialize[Task[File]] = Def.taskDyn {
    val v = sbtVersion.value
    Def.task {
      val Seq(launcher) = getJarFiles("org.scala-sbt" % "sbt-launch" % v).value
      launcher
    }
  }
}
