package io.ge.wawo.agent.terminal

import io.ge.wawo.agent.environment.EnvironmentSnapshot

interface TerminalBackend {
    suspend fun start(
        environment: EnvironmentSnapshot,
        argv: List<String>,
        workingDirectory: String?,
        environmentVariables: Map<String, String>,
        cols: Int,
        rows: Int,
    ): TerminalBackendStart
}
