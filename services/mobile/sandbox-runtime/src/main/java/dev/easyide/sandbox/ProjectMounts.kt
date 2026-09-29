package dev.easyide.sandbox

import dev.easyide.sandbox.backend.GuestBind
import dev.easyide.sandbox.model.ProjectRecord
import java.io.File

/**
 * Where each project of one environment appears inside it: `/projects/<name>`, in every
 * terminal, so a person or an agent can `ls /projects` and reach a sibling project. The
 * environment is one OS; its projects are the folders of one tree.
 *
 * [current] is where a terminal starts; [siblings] are the other projects' binds.
 */
class ProjectMounts(val current: String, val siblings: List<GuestBind>) {

    companion object {
        private const val FALLBACK = "project"
        private val UNSAFE = Regex("[^A-Za-z0-9._-]+")

        /**
         * A folder name per project: the project's name with anything a shell or a path
         * would trip over replaced by `-`. Clashes (two names that slug alike, or a slug
         * that is `.`/`..`) get `-2`, `-3`, ... The oldest project keeps the plain name, so
         * adding a project never renames an existing one.
         */
        fun folderNames(projects: List<ProjectRecord>): Map<String, String> {
            val taken = HashSet<String>()
            val result = HashMap<String, String>()
            for (p in projects.sortedWith(compareBy({ it.createdAtEpochMs }, { it.id }))) {
                val base = p.name.trim().replace(UNSAFE, "-").trim('-', '.').ifEmpty { FALLBACK }
                var name = base
                var n = 2
                while (!taken.add(name.lowercase())) name = "$base-${n++}"
                result[p.id] = name
            }
            return result
        }

        fun resolve(paths: SandboxPaths, environmentId: String, hostProjectDir: File, all: List<ProjectRecord>): ProjectMounts {
            val here = all.filter { it.environmentId == environmentId }
            val names = folderNames(here)
            fun guest(id: String) = "${SandboxPaths.GUEST_PROJECTS}/${names.getValue(id)}"
            val current = names[hostProjectDir.name]?.let { guest(hostProjectDir.name) }
                ?: "${SandboxPaths.GUEST_PROJECTS}/${hostProjectDir.name}"
            val siblings = here.filter { it.id != hostProjectDir.name }
                .map { GuestBind(paths.projectDir(it.id), guest(it.id)) }
            return ProjectMounts(current, siblings)
        }
    }
}
