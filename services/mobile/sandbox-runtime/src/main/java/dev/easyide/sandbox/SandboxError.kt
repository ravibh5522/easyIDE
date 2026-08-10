package dev.easyide.sandbox

/**
 * Failures callers are expected to handle. Anything not represented here is a
 * programming error and is allowed to propagate.
 */
sealed class SandboxError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class EnvironmentNotFound(id: String) :
        SandboxError("No sandbox environment with id '$id'")

    class ProjectNotFound(id: String) :
        SandboxError("No project with id '$id'")

    /** Refusing to delete an environment that projects still reference. */
    class EnvironmentInUse(id: String, val projectNames: List<String>) :
        SandboxError("Environment '$id' is still used by: ${projectNames.joinToString()}")

    class EnvironmentNotReady(id: String, state: String) :
        SandboxError("Environment '$id' is not ready (state=$state)")

    class ProvisioningFailed(id: String, reason: String, cause: Throwable? = null) :
        SandboxError("Provisioning environment '$id' failed: $reason", cause)

    class StorageFailure(operation: String, cause: Throwable? = null) :
        SandboxError("Storage operation '$operation' failed", cause)

    class BackendUnavailable(backend: String, reason: String) :
        SandboxError("Sandbox backend '$backend' unavailable: $reason")

    class DuplicateName(name: String) :
        SandboxError("A project named '$name' already exists")
}
