/*
   Copyright 2017-2020 Charles Korn.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package batect.ioc

import batect.VersionInfo
import batect.cli.CommandLineOptions
import batect.cli.commands.BackgroundTaskManager
import batect.cli.commands.CleanupCachesCommand
import batect.cli.commands.CommandFactory
import batect.cli.commands.DisableTelemetryCommand
import batect.cli.commands.DockerConnectivity
import batect.cli.commands.EnableTelemetryCommand
import batect.cli.commands.HelpCommand
import batect.cli.commands.ListTasksCommand
import batect.cli.commands.RunTaskCommand
import batect.cli.commands.UpgradeCommand
import batect.cli.commands.VersionInfoCommand
import batect.config.ProjectPaths
import batect.config.includes.GitRepositoryCache
import batect.config.includes.GitRepositoryCacheCleanupTask
import batect.config.includes.GitRepositoryCacheNotificationListener
import batect.config.includes.IncludeResolver
import batect.config.io.ConfigurationLoader
import batect.docker.DockerHostNameResolver
import batect.docker.DockerHttpConfig
import batect.docker.DockerTLSConfig
import batect.docker.api.ContainersAPI
import batect.docker.api.ExecAPI
import batect.docker.api.ImagesAPI
import batect.docker.api.NetworksAPI
import batect.docker.api.SystemInfoAPI
import batect.docker.api.VolumesAPI
import batect.docker.build.DockerIgnoreParser
import batect.docker.build.DockerImageBuildContextFactory
import batect.docker.build.DockerfileParser
import batect.docker.client.DockerClient
import batect.docker.client.DockerContainersClient
import batect.docker.client.DockerExecClient
import batect.docker.client.DockerImagesClient
import batect.docker.client.DockerNetworksClient
import batect.docker.client.DockerSystemInfoClient
import batect.docker.client.DockerVolumesClient
import batect.docker.pull.DockerRegistryCredentialsConfigurationFile
import batect.docker.pull.DockerRegistryCredentialsProvider
import batect.docker.pull.DockerRegistryDomainResolver
import batect.docker.pull.DockerRegistryIndexResolver
import batect.docker.run.ContainerIOStreamer
import batect.docker.run.ContainerTTYManager
import batect.docker.run.ContainerWaiter
import batect.execution.ConfigVariablesProvider
import batect.execution.ContainerEntrypointResolver
import batect.execution.InterruptionTrap
import batect.execution.RunOptions
import batect.execution.TaskExecutionOrderResolver
import batect.execution.TaskSuggester
import batect.git.GitClient
import batect.io.ApplicationPaths
import batect.logging.ApplicationInfoLogger
import batect.logging.HttpLoggingInterceptor
import batect.logging.LogMessageWriter
import batect.logging.LoggerFactory
import batect.logging.StandardAdditionalDataSource
import batect.logging.singletonWithLogger
import batect.os.ConsoleDimensions
import batect.os.ConsoleInfo
import batect.os.ConsoleManager
import batect.os.NativeMethods
import batect.os.ProcessRunner
import batect.os.SignalListener
import batect.os.SystemInfo
import batect.os.unix.ApplicationResolver
import batect.os.unix.UnixConsoleManager
import batect.os.windows.WindowsConsoleManager
import batect.proxies.ProxyEnvironmentVariablePreprocessor
import batect.proxies.ProxyEnvironmentVariablesProvider
import batect.telemetry.AbacusClient
import batect.telemetry.CIEnvironmentDetector
import batect.telemetry.EnvironmentTelemetryCollector
import batect.telemetry.TelemetryConfigurationStore
import batect.telemetry.TelemetryConsent
import batect.telemetry.TelemetryConsentPrompt
import batect.telemetry.TelemetryManager
import batect.telemetry.TelemetryUploadQueue
import batect.telemetry.TelemetryUploadTask
import batect.ui.Console
import batect.ui.Prompt
import batect.ui.fancy.StartupProgressDisplayProvider
import batect.updates.UpdateInfoDownloader
import batect.updates.UpdateInfoStorage
import batect.updates.UpdateInfoUpdater
import batect.updates.UpdateNotifier
import batect.utils.Json
import batect.wrapper.WrapperCache
import batect.wrapper.WrapperCacheCleanupTask
import jnr.ffi.Platform
import okhttp3.OkHttpClient
import org.kodein.di.DI
import org.kodein.di.DirectDI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.singleton

val rootModule = DI.Module("root") {
    import(cliModule)
    import(configModule)
    import(dockerModule)
    import(gitModule)
    import(ioModule)
    import(iocModule)
    import(executionModule)
    import(loggingModule)
    import(osModule)
    import(proxiesModule)
    import(telemetryModule)
    import(uiModule)
    import(updatesModule)
    import(wrapperModule)
    import(coreModule)

    bind<OkHttpClient>() with singleton {
        OkHttpClient.Builder()
            .addInterceptor(instance<HttpLoggingInterceptor>())
            .build()
    }

    if (Platform.getNativePlatform().os in setOf(Platform.OS.DARWIN, Platform.OS.LINUX)) {
        import(unixModule)
    }

    if (Platform.getNativePlatform().os == Platform.OS.WINDOWS) {
        import(windowsModule)
    }
}

private val cliModule = DI.Module("cli") {
    bind<CommandFactory>() with singleton { CommandFactory() }

    bind<RunTaskCommand>() with singleton {
        RunTaskCommand(
            commandLineOptions().configurationFileName,
            instance(RunOptionsType.Overall),
            instance(),
            instance(),
            instance(),
            instance(),
            instance(),
            commandLineOptions().requestedOutputStyle,
            instance(StreamType.Output),
            instance()
        )
    }

    bind<BackgroundTaskManager>() with singleton { BackgroundTaskManager(instance(), instance(), instance()) }
    bind<CleanupCachesCommand>() with singleton { CleanupCachesCommand(instance(), instance(), instance(), instance(StreamType.Output)) }
    bind<DisableTelemetryCommand>() with singleton { DisableTelemetryCommand(instance(), instance(), instance(StreamType.Output)) }
    bind<DockerConnectivity>() with singleton { DockerConnectivity(instance(), instance(), instance(StreamType.Error)) }
    bind<EnableTelemetryCommand>() with singleton { EnableTelemetryCommand(instance(), instance(StreamType.Output)) }
    bind<HelpCommand>() with singleton { HelpCommand(instance(), instance(StreamType.Output), instance()) }
    bind<ListTasksCommand>() with singleton { ListTasksCommand(commandLineOptions().configurationFileName, instance(), instance(StreamType.Output)) }
    bind<VersionInfoCommand>() with singleton { VersionInfoCommand(instance(), instance(StreamType.Output), instance(), instance(), instance(), instance()) }
    bind<UpgradeCommand>() with singletonWithLogger { logger -> UpgradeCommand(instance(), instance(), instance(), instance(), instance(StreamType.Output), instance(StreamType.Error), instance(), instance(), logger) }
}

private val configModule = DI.Module("config") {
    bind<ConfigurationLoader>() with singletonWithLogger { logger -> ConfigurationLoader(instance(), instance(), instance(), logger) }
    bind<GitRepositoryCache>() with singleton { GitRepositoryCache(instance(), instance(), instance(), instance()) }
    bind<GitRepositoryCacheCleanupTask>() with singletonWithLogger { logger -> GitRepositoryCacheCleanupTask(instance(), instance(), logger) }
    bind<GitRepositoryCacheNotificationListener>() with singleton { GitRepositoryCacheNotificationListener(instance(StreamType.Output), commandLineOptions().requestedOutputStyle) }
    bind<IncludeResolver>() with singleton { IncludeResolver(instance()) }
    bind<ProjectPaths>() with singleton { ProjectPaths(commandLineOptions().configurationFileName) }
}

private val dockerModule = DI.Module("docker") {
    import(dockerApiModule)
    import(dockerClientModule)

    bind<ContainerIOStreamer>() with singleton { ContainerIOStreamer() }
    bind<ContainerTTYManager>() with singletonWithLogger { logger -> ContainerTTYManager(instance(), instance(), logger) }
    bind<ContainerWaiter>() with singleton { ContainerWaiter(instance()) }
    bind<DockerfileParser>() with singleton { DockerfileParser() }
    bind<DockerIgnoreParser>() with singleton { DockerIgnoreParser() }
    bind<DockerImageBuildContextFactory>() with singleton { DockerImageBuildContextFactory(instance()) }
    bind<DockerHostNameResolver>() with singleton { DockerHostNameResolver(instance(), instance()) }
    bind<DockerHttpConfig>() with singleton { DockerHttpConfig(instance(), commandLineOptions().dockerHost, instance(), instance()) }
    bind<DockerRegistryCredentialsConfigurationFile>() with singletonWithLogger { logger -> DockerRegistryCredentialsConfigurationFile(instance(), instance(), logger) }
    bind<DockerRegistryCredentialsProvider>() with singleton { DockerRegistryCredentialsProvider(instance(), instance(), instance()) }
    bind<DockerRegistryDomainResolver>() with singleton { DockerRegistryDomainResolver() }
    bind<DockerRegistryIndexResolver>() with singleton { DockerRegistryIndexResolver() }

    bind<DockerTLSConfig>() with singleton {
        val options = commandLineOptions()

        if (options.dockerUseTLS) {
            DockerTLSConfig.EnableTLS(options.dockerVerifyTLS, options.dockerTlsCACertificatePath, options.dockerTLSCertificatePath, options.dockerTLSKeyPath)
        } else {
            DockerTLSConfig.DisableTLS
        }
    }
}

private val dockerApiModule = DI.Module("docker.api") {
    bind<ContainersAPI>() with singletonWithLogger { logger -> ContainersAPI(instance(), instance(), logger) }
    bind<ExecAPI>() with singletonWithLogger { logger -> ExecAPI(instance(), instance(), logger) }
    bind<ImagesAPI>() with singletonWithLogger { logger -> ImagesAPI(instance(), instance(), logger) }
    bind<NetworksAPI>() with singletonWithLogger { logger -> NetworksAPI(instance(), instance(), logger) }
    bind<SystemInfoAPI>() with singletonWithLogger { logger -> SystemInfoAPI(instance(), instance(), logger) }
    bind<VolumesAPI>() with singletonWithLogger { logger -> VolumesAPI(instance(), instance(), logger) }
}

private val dockerClientModule = DI.Module("docker.client") {
    bind<DockerContainersClient>() with singletonWithLogger { logger -> DockerContainersClient(instance(), instance(), instance(), instance(), instance(), logger) }
    bind<DockerExecClient>() with singletonWithLogger { logger -> DockerExecClient(instance(), instance(), logger) }
    bind<DockerImagesClient>() with singletonWithLogger { logger -> DockerImagesClient(instance(), instance(), instance(), instance(), logger) }
    bind<DockerNetworksClient>() with singleton { DockerNetworksClient(instance()) }
    bind<DockerSystemInfoClient>() with singletonWithLogger { logger -> DockerSystemInfoClient(instance(), instance(), logger) }
    bind<DockerVolumesClient>() with singleton { DockerVolumesClient(instance()) }
    bind<DockerClient>() with singleton { DockerClient(instance(), instance(), instance(), instance(), instance(), instance()) }
}

private val gitModule = DI.Module("git") {
    bind<GitClient>() with singleton { GitClient(instance()) }
}

private val ioModule = DI.Module("io") {
    bind<ApplicationPaths>() with singleton { ApplicationPaths(instance<SystemInfo>()) }
}

private val iocModule = DI.Module("ioc") {
    bind<DockerConfigurationKodeinFactory>() with singleton { DockerConfigurationKodeinFactory(directDI) }
}

private val executionModule = DI.Module("execution") {
    bind<ConfigVariablesProvider>() with singleton { ConfigVariablesProvider(commandLineOptions().configVariableOverrides, commandLineOptions().configVariablesSourceFile, instance()) }
    bind<ContainerEntrypointResolver>() with singleton { ContainerEntrypointResolver() }
    bind<InterruptionTrap>() with singleton { InterruptionTrap(instance()) }
    bind<RunOptions>(RunOptionsType.Overall) with singleton { RunOptions(commandLineOptions()) }
    bind<TaskExecutionOrderResolver>() with singletonWithLogger { logger -> TaskExecutionOrderResolver(instance(), instance(), logger) }
    bind<TaskSuggester>() with singleton { TaskSuggester() }
}

private val loggingModule = DI.Module("logging") {
    bind<ApplicationInfoLogger>() with singletonWithLogger { logger -> ApplicationInfoLogger(logger, instance(), instance(), instance(), instance(), instance(), instance()) }
    bind<HttpLoggingInterceptor>() with singletonWithLogger { logger -> HttpLoggingInterceptor(logger) }
    bind<LoggerFactory>() with singleton { LoggerFactory(instance()) }
    bind<LogMessageWriter>() with singleton { LogMessageWriter(Json.forLogging) }
    bind<StandardAdditionalDataSource>() with singleton { StandardAdditionalDataSource(instance()) }
}

private val osModule = DI.Module("os") {
    bind<ConsoleDimensions>() with singletonWithLogger { logger -> ConsoleDimensions(instance(), instance(), logger) }
    bind<ConsoleInfo>() with singletonWithLogger { logger -> ConsoleInfo(instance(), instance(), instance(), logger) }
    bind<ProcessRunner>() with singletonWithLogger { logger -> ProcessRunner(logger) }
    bind<SignalListener>() with singleton { SignalListener(instance()) }
}

private val proxiesModule = DI.Module("proxies") {
    bind<ProxyEnvironmentVariablePreprocessor>() with singletonWithLogger { logger -> ProxyEnvironmentVariablePreprocessor(instance(), logger) }
    bind<ProxyEnvironmentVariablesProvider>() with singleton { ProxyEnvironmentVariablesProvider(instance(), instance()) }
}

private val telemetryModule = DI.Module("telemetry") {
    bind<AbacusClient>() with singletonWithLogger { logger -> AbacusClient(instance(), logger) }
    bind<CIEnvironmentDetector>() with singleton { CIEnvironmentDetector(instance()) }
    bind<EnvironmentTelemetryCollector>() with singleton { EnvironmentTelemetryCollector(instance(), instance(), instance(), instance(), instance(), instance()) }
    bind<TelemetryConfigurationStore>() with singletonWithLogger { logger -> TelemetryConfigurationStore(instance(), logger) }
    bind<TelemetryConsent>() with singleton { TelemetryConsent(commandLineOptions().disableTelemetry, instance()) }
    bind<TelemetryConsentPrompt>() with singleton { TelemetryConsentPrompt(instance(), commandLineOptions().disableTelemetry, commandLineOptions().requestedOutputStyle, instance(), instance(), instance(StreamType.Output), instance()) }
    bind<TelemetryManager>() with singletonWithLogger { logger -> TelemetryManager(instance(), instance(), instance(), instance(), instance(), logger) }
    bind<TelemetryUploadQueue>() with singletonWithLogger { logger -> TelemetryUploadQueue(instance(), logger) }
    bind<TelemetryUploadTask>() with singletonWithLogger { logger -> TelemetryUploadTask(instance(), instance(), instance(), instance(), logger) }
}

private val unixModule = DI.Module("os.unix") {
    bind<ApplicationResolver>() with singleton { ApplicationResolver(instance()) }
    bind<ConsoleManager>() with singletonWithLogger { logger -> UnixConsoleManager(instance(), instance(), instance(), logger) }
}

private val windowsModule = DI.Module("os.windows") {
    bind<ConsoleManager>() with singletonWithLogger { logger -> WindowsConsoleManager(instance(), instance(), logger) }
}

private val uiModule = DI.Module("ui") {
    bind<Console>(StreamType.Output) with singleton { Console(instance(StreamType.Output), enableComplexOutput = !commandLineOptions().disableColorOutput && nativeMethods().determineIfStdoutIsTTY(), consoleDimensions = instance()) }
    bind<Console>(StreamType.Error) with singleton { Console(instance(StreamType.Error), enableComplexOutput = !commandLineOptions().disableColorOutput && nativeMethods().determineIfStderrIsTTY(), consoleDimensions = instance()) }
    bind<Prompt>() with singleton { Prompt(instance(StreamType.Output), instance(StreamType.Input)) }
    bind<StartupProgressDisplayProvider>() with singleton { StartupProgressDisplayProvider(instance()) }
}

private val updatesModule = DI.Module("updates") {
    bind<UpdateInfoDownloader>() with singletonWithLogger { logger -> UpdateInfoDownloader(instance(), logger) }
    bind<UpdateInfoStorage>() with singletonWithLogger { logger -> UpdateInfoStorage(instance(), logger) }
    bind<UpdateInfoUpdater>() with singletonWithLogger { logger -> UpdateInfoUpdater(instance(), instance(), instance(), logger) }
    bind<UpdateNotifier>() with singletonWithLogger { logger -> UpdateNotifier(commandLineOptions().disableUpdateNotification, instance(), instance(), instance(), instance(StreamType.Output), instance(), logger) }
}

private val wrapperModule = DI.Module("wrapper") {
    bind<WrapperCache>() with singletonWithLogger { logger -> WrapperCache(instance(), instance(), logger) }
    bind<WrapperCacheCleanupTask>() with singletonWithLogger { logger -> WrapperCacheCleanupTask(!commandLineOptions().disableWrapperCacheCleanup, instance(), instance(), instance(), logger) }
}

private val coreModule = DI.Module("core") {
    bind<VersionInfo>() with singleton { VersionInfo() }
}

fun DirectDI.commandLineOptions(): CommandLineOptions = this.instance()
private fun DirectDI.nativeMethods(): NativeMethods = this.instance()
