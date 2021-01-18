package org.javacs.kt.classpath

import java.nio.file.Path
import java.nio.file.Paths
import org.javacs.kt.LOG
import org.javacs.kt.util.findCommandOnPath
import org.javacs.kt.util.execAndReadStdoutAndStderr

/** Resolver for reading maven dependencies */
internal class TychoClassPathResolver private constructor(private val pom: Path) : ClassPathResolver {
    override val resolverType: String = "Tycho"
    override val classpath: Set<Path> get() {
        val tychoOutput = generateMavenDependencyList(pom)
        val artifacts = readMavenDependencyList(tychoOutput, pom.toAbsolutePath().parent.parent)

        when {
            artifacts.isEmpty() -> LOG.warn("No artifacts found in {}", pom)
            artifacts.size < 5 -> LOG.info("Found {} in {}", artifacts, pom)
            else -> LOG.info("Found {} artifacts in {}", artifacts.size, pom)
        }

        return artifacts.mapNotNull { it.listDependencies() }.flatten().toSet().union(setOf(pom.toAbsolutePath().parent.resolve("bin")))
    }

    companion object {
        /** Create a maven resolver if a file is a pom. */
        fun maybeCreate(file: Path): TychoClassPathResolver? =
            file.takeIf { it.endsWith("pom.xml") }?.let { TychoClassPathResolver(it) }
    }
}

private val artifactPattern = "^[^:]+:(?:[^:]+:)+[^:]+".toRegex()

private fun readMavenDependencyList(tychoOutput: Path, basePath: Path): Set<TychoDeps> =
    tychoOutput.toFile()
        .readLines()
        .map { TychoDeps(Paths.get(it), basePath) }
        .toSet()

private fun generateMavenDependencyList(pom: Path): Path {
    val tychoOutput = pom.toAbsolutePath().parent.resolve("target").resolve("dependencies-list.txt")
    val cmd = "$mvnCommand org.eclipse.tycho.extras:tycho-dependency-tools-plugin:list-dependencies"
    val workingDirectory = pom.toAbsolutePath().parent
    LOG.info("Run {} in {}", cmd, workingDirectory)
    val (result, errors) = execAndReadStdoutAndStderr(cmd, workingDirectory)
    LOG.debug(result)
    if ("BUILD FAILURE" in errors) {
        LOG.warn("Maven task failed: {}", errors.lines().firstOrNull())
    }

    return tychoOutput
}

private val mvnCommand: Path by lazy {
    requireNotNull(findCommandOnPath("mvn")) { "Unable to find the 'mvn' command" }
}

data class TychoDeps(
    val path: Path,
    val basePathProjects: Path
) {
    fun listDependencies(): List<Path> {
        var project = path.toFile().nameWithoutExtension.toString()

        val pattern = "-\\d+\\.\\d+\\.\\d+-SNAPSHOT".toRegex()
        if (project.contains(pattern)) {
            project = project.replace(pattern,"");

            var sourcePath = basePathProjects.resolve(project)

            if(sourcePath.toFile().exists()) {
                LOG.info("found source entry {} ", sourcePath)
                return listOf(sourcePath.resolve("bin"), sourcePath.resolve(".kotlin-eclipse").resolve("classes"))
            }
        }
        LOG.info("found jar entry {} ", path)
        return listOf(path)
    }

    override fun toString() = if (listDependencies().size == 1) "$path" else "/{$path.toFile().nameWithoutExtension}"
}