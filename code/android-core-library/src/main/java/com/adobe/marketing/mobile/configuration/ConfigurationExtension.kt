/*
  Copyright 2022 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
 */

package com.adobe.marketing.mobile.configuration

import androidx.annotation.VisibleForTesting
import com.adobe.marketing.mobile.Event
import com.adobe.marketing.mobile.Extension
import com.adobe.marketing.mobile.ExtensionApi
import com.adobe.marketing.mobile.ExtensionEventListener
import com.adobe.marketing.mobile.LoggingMode
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.internal.compatibility.CacheManager
import com.adobe.marketing.mobile.internal.eventhub.EventHub
import com.adobe.marketing.mobile.launch.rulesengine.LaunchRulesEngine
import com.adobe.marketing.mobile.launch.rulesengine.LaunchRulesEvaluator
import com.adobe.marketing.mobile.services.CacheFileService
import com.adobe.marketing.mobile.services.ServiceProvider
import java.lang.Exception
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Configuration Extension.
 * Responsible for retrieving the configuration of the SDK, updating the shared state and
 * dispatching configuration updates through the `EventHub`
 */
internal class ConfigurationExtension : Extension {

    companion object {
        private const val TAG = "ConfigurationExtension"
        private const val EXTENSION_NAME = "com.adobe.module.configuration"
        private const val EVENT_TYPE = "com.adobe.eventType.configuration"
        private const val EVENT_SOURCE_REQUEST_CONTENT = "com.adobe.eventSource.requestContent"
        private const val EVENT_SOURCE_RESPONSE_CONTENT = "com.adobe.eventSource.responseContent"

        internal const val CONFIGURATION_REQUEST_CONTENT_JSON_APP_ID = "config.appId"
        internal const val CONFIGURATION_REQUEST_CONTENT_JSON_FILE_PATH = "config.filePath"
        internal const val CONFIGURATION_REQUEST_CONTENT_JSON_ASSET_FILE = "config.assetFile"
        internal const val CONFIGURATION_REQUEST_CONTENT_UPDATE_CONFIG = "config.update"
        internal const val CONFIGURATION_REQUEST_CONTENT_CLEAR_UPDATED_CONFIG =
            "config.clearUpdates"
        internal const val CONFIGURATION_REQUEST_CONTENT_RETRIEVE_CONFIG = "config.getData"
        internal const val CONFIGURATION_REQUEST_CONTENT_IS_INTERNAL_EVENT =
            "config.isinternalevent"

        internal const val DATASTORE_KEY = "AdobeMobile_ConfigState"
        internal const val RULES_CONFIG_URL = "rules.url"

        internal const val CONFIG_DOWNLOAD_RETRY_ATTEMPT_DELAY_MS = 5L
        internal const val CONFIGURATION_RESPONSE_IDENTITY_ALL_IDENTIFIERS = "config.allIdentifiers"
        internal const val EVENT_STATE_OWNER = "stateowner"
        internal const val GLOBAL_CONFIG_PRIVACY = "global.privacy"
    }

    private val serviceProvider: ServiceProvider
    private val appIdManager: AppIdManager
    private val cacheFileService: CacheFileService
    private val launchRulesEvaluator: LaunchRulesEvaluator
    private val configurationStateManager: ConfigurationStateManager
    private val configurationRulesManager: ConfigurationRulesManager
    private val retryWorker: ScheduledExecutorService
    private val extensionEventListener: ExtensionEventListener = ExtensionEventListener {
        handleConfigurationRequestEvent(it)
    }

    constructor(extensionApi: ExtensionApi) : this(extensionApi, ServiceProvider.getInstance())

    /**
     * Exists only for cascading components for dependency injection.
     */
    private constructor(
        extensionApi: ExtensionApi,
        serviceProvider: ServiceProvider
    ) : this(
        extensionApi, serviceProvider,
        AppIdManager(serviceProvider.dataStoreService, serviceProvider.deviceInfoService),
        CacheManager(serviceProvider.deviceInfoService),
        LaunchRulesEvaluator("Configuration", LaunchRulesEngine(extensionApi), extensionApi),
        Executors.newSingleThreadScheduledExecutor()
    )

    /**
     * Exists only for cascading components for dependency injection.
     */
    private constructor(
        extensionApi: ExtensionApi,
        serviceProvider: ServiceProvider,
        appIdManager: AppIdManager,
        cacheFileService: CacheFileService,
        launchRulesEvaluator: LaunchRulesEvaluator,
        retryWorker: ScheduledExecutorService
    ) : this(
        extensionApi,
        serviceProvider,
        appIdManager,
        cacheFileService,
        launchRulesEvaluator,
        retryWorker,
        ConfigurationStateManager(
            appIdManager,
            cacheFileService,
            serviceProvider.networkService,
            serviceProvider.deviceInfoService,
            serviceProvider.dataStoreService
        ),
        ConfigurationRulesManager(
            launchRulesEvaluator,
            serviceProvider.dataStoreService,
            serviceProvider.networkService,
            cacheFileService
        )
    )

    @VisibleForTesting
    internal constructor(
        extensionApi: ExtensionApi,
        serviceProvider: ServiceProvider,
        appIdManager: AppIdManager,
        cacheFileService: CacheFileService,
        launchRulesEvaluator: LaunchRulesEvaluator,
        retryWorker: ScheduledExecutorService,
        configurationStateManager: ConfigurationStateManager,
        configurationRulesManager: ConfigurationRulesManager
    ) : super(extensionApi) {
        this.serviceProvider = serviceProvider
        this.appIdManager = appIdManager
        this.cacheFileService = cacheFileService
        this.launchRulesEvaluator = launchRulesEvaluator
        this.retryWorker = retryWorker
        this.configurationStateManager = configurationStateManager
        this.configurationRulesManager = configurationRulesManager
    }

    /**
     * Sets up the Configuration extension for the SDK by loading the appId, publishing initial state
     * and registering relevant event listener for processing events.
     */
    override fun onRegistered() {
        super.onRegistered()

        // TODO: Register pre-processor
        api.registerEventListener(
            EVENT_TYPE,
            EVENT_SOURCE_REQUEST_CONTENT,
            extensionEventListener
        )

        val appId = this.appIdManager.loadAppId()

        if (!appId.isNullOrBlank()) {
            val eventData: MutableMap<String, Any?> =
                mutableMapOf(
                    CONFIGURATION_REQUEST_CONTENT_JSON_APP_ID to appId,
                    CONFIGURATION_REQUEST_CONTENT_IS_INTERNAL_EVENT to true
                )
            dispatchConfigurationRequest(eventData)
        }

        val initialConfig: Map<String, Any?> = this.configurationStateManager.loadInitialConfig()

        if (initialConfig.isNotEmpty()) {
            publishConfigurationChanges(null, true)
        }
    }

    override fun getName(): String {
        return EXTENSION_NAME
    }

    override fun getFriendlyName(): String {
        return EXTENSION_NAME
    }

    /**
     * Responsible for handling the incoming event requests directed towards [ConfigurationExtension].
     *
     * @param event the event request dispatched to the [ConfigurationExtension]
     */
    internal fun handleConfigurationRequestEvent(event: Event) {
        if (event.eventData == null) return

        when {
            event.eventData.containsKey(CONFIGURATION_REQUEST_CONTENT_JSON_APP_ID) -> {
                configureWithAppID(event)
            }
            event.eventData.containsKey(CONFIGURATION_REQUEST_CONTENT_JSON_ASSET_FILE) -> {
                configureWithFileAsset(event)
            }
            event.eventData.containsKey(CONFIGURATION_REQUEST_CONTENT_JSON_FILE_PATH) -> {
                configureWithFilePath(event)
            }
            event.eventData.containsKey(CONFIGURATION_REQUEST_CONTENT_UPDATE_CONFIG) -> {
                updateConfiguration(event)
            }
            event.eventData.containsKey(CONFIGURATION_REQUEST_CONTENT_CLEAR_UPDATED_CONFIG) -> {
                clearUpdatedConfiguration(event)
            }
            event.eventData.containsKey(CONFIGURATION_REQUEST_CONTENT_RETRIEVE_CONFIG) -> {
                retrieveConfiguration(event)
            }
        }
    }

    /**
     * Downloads the configuration file based on the appId provided with the [event] and updates
     * the current app configuration with the resulting downloaded configuration.
     *
     * @param event the event requesting/triggering an update to configuration with appId.
     */
    private fun configureWithAppID(event: Event) {
        val appID = event.eventData[CONFIGURATION_REQUEST_CONTENT_JSON_APP_ID] as? String

        if (appID.isNullOrBlank()) {
            appIdManager.removeAppIDFromPersistence()

            publishConfigurationState(
                configurationStateManager.environmentAwareConfiguration,
                event
            )
            return
        }

        if (!configurationStateManager.hasConfigExpired(appID)) {
            publishConfigurationState(
                configurationStateManager.environmentAwareConfiguration,
                event
            )
            return
        }

        // Stop all event processing for the extension until new configuration is downloaded
        api.stopEvents()

        configurationStateManager.updateConfigWithAppId(appID) { config ->
            if (config != null) {
                publishConfigurationChanges(event, false)
                // re-start event processign after new configuration download
                api.startEvents()
            } else {
                // If the configuration download fails, retry download again.
                retryWorker.schedule({ configureWithAppID(event) }, CONFIG_DOWNLOAD_RETRY_ATTEMPT_DELAY_MS, TimeUnit.SECONDS)
            }
        }
    }

    /**
     * Retrieves the configuration from the file path specified in the [event]'s data and replaces
     * the current configuration with it.
     *
     * @param event the trigger event (whose event data contains the file path for retrieving the config)
     *              requesting a configuration change
     */
    private fun configureWithFilePath(event: Event) {
        val filePath = event.eventData[CONFIGURATION_REQUEST_CONTENT_JSON_FILE_PATH] as String?

        if (filePath.isNullOrBlank()) {
            MobileCore.log(
                LoggingMode.WARNING,
                TAG,
                "Unable to read config from provided file (filePath is invalid)"
            )
            return
        }

        val config = configurationStateManager.getConfigFromFile(filePath)
        if (config == null) {
            MobileCore.log(
                LoggingMode.WARNING,
                TAG,
                "Unable to read config from provided file (content is invalid)"
            )
            return
        }

        configurationStateManager.replaceConfiguration(config)
        publishConfigurationChanges(event, false)
    }

    /**
     * Updates the current configuration with the content from [ConfigurationStateManager.CONFIG_BUNDLED_FILE_NAME]
     *
     * @param event which contains [CONFIGURATION_REQUEST_CONTENT_JSON_ASSET_FILE] as part of its event data
     */
    private fun configureWithFileAsset(event: Event) {
        val config =
            configurationStateManager.getBundledConfig(ConfigurationStateManager.CONFIG_BUNDLED_FILE_NAME)

        if (config.isNullOrEmpty()) {
            MobileCore.log(
                LoggingMode.DEBUG,
                TAG,
                "Empty configuration found when processing JSON string."
            )
            return
        }

        configurationStateManager.replaceConfiguration(config)
        publishConfigurationChanges(event, false)
    }

    /**
     * Updates the programmatic configuration with the programmatic config in [event]'s data.
     *
     * @param event the event containing programmatic configuration that is to be applied on the
     *              current configuration
     */
    private fun updateConfiguration(event: Event) {
        val config: MutableMap<*, *> =
            event.eventData[CONFIGURATION_REQUEST_CONTENT_UPDATE_CONFIG] as?
                MutableMap<*, *> ?: return

        if (!config.keys.isAllString()) {
            MobileCore.log(
                LoggingMode.DEBUG,
                TAG,
                "Invalid configuration. Configuration contains non string keys."
            )
            return
        }

        val programmaticConfig = try {
            config as? Map<String, Any?>
        } catch (e: Exception) {
            null
        }

        programmaticConfig?.let {
            configurationStateManager.updateProgrammaticConfig(it)
            publishConfigurationChanges(event, false)
        }
    }

    /**
     * Clears any updates made to the configuration (more specifically the programmatic config)
     * maintained by the extension.
     *
     * @param event an event requesting configuration to be cleared.
     */
    private fun clearUpdatedConfiguration(event: Event) {
        configurationStateManager.clearProgrammaticConfig()
        publishConfigurationChanges(event, false)
    }

    /**
     * Dispatches an event containing the current configuration
     *
     * @param event the event to which the current configuration should be
     *        dispatched as a response
     */
    private fun retrieveConfiguration(event: Event) {
        dispatchConfigurationResponse(
            configurationStateManager.environmentAwareConfiguration,
            event
        )
    }

    /**
     * Does three things
     *  - Publishes the current configuration as configuration state at [triggerEvent]
     *  - Dispatches an event response to the [triggerEvent]
     *  - Replaces current rules and applies the new rules as necessary
     *
     *  @param triggerEvent the event that triggered the configuration change
     *  @param useCachedRules whether cached rules should be used instead of downloading
     */
    private fun publishConfigurationChanges(triggerEvent: Event?, useCachedRules: Boolean) {
        val config = configurationStateManager.environmentAwareConfiguration
        publishConfigurationState(config, triggerEvent)
        dispatchConfigurationResponse(config, triggerEvent)
        replaceRules(config, useCachedRules)
    }

    /**
     * Dispatches a configuration response event in response to to [triggerEvent]
     *
     * @param eventData the content of the event data for the response event
     * @param triggerEvent the [Event] to which the response is being dispatched
     */
    private fun dispatchConfigurationResponse(eventData: Map<String, Any?>, triggerEvent: Event?) {
        val event = Event.Builder(
            "Configuration Response Event",
            EVENT_TYPE, EVENT_SOURCE_RESPONSE_CONTENT
        )
            .setEventData(eventData)
            .build()
//
//        MobileCore.dispatchResponseEvent(event, triggerEvent) {
//            MobileCore.log(LoggingMode.ERROR, TAG, "${it.errorCode}-${it.errorName}")
//        }
    }

    private fun dispatchConfigurationRequest(eventData: Map<String, Any?>) {
        val event = Event.Builder(
            "Configure with AppID Internal",
            EVENT_TYPE, EVENT_SOURCE_REQUEST_CONTENT
        )
            .setEventData(eventData).build()
        MobileCore.dispatchEvent(event) {
            MobileCore.log(LoggingMode.ERROR, TAG, "${it.errorCode}-${it.errorName}")
        }
    }

    /**
     * Publishes the state of [ConfigurationExtension] to [EventHub]
     *
     * @param state the configuration state that is to be published
     * @param event the event (if any) to which the state is to be published as a response.
     */
    private fun publishConfigurationState(state: Map<String, Any?>, event: Event?) {
        val successful = api.createSharedState(state, event)
        if (!successful) {
            MobileCore.log(
                LoggingMode.ERROR,
                TAG,
                "Failed to update configuration state."
            )
        }
    }

    /**
     * Replaces the existing rules in the rules engine.
     *
     * @param config the configuration from which the rules url is extracted
     * @param useCache whether or not the rules url from the cached to be used
     */
    private fun replaceRules(config: Map<String, Any?>, useCache: Boolean) {
        if (useCache) {
            configurationRulesManager.applyCachedRules(api)
        } else {
            val rulesURL: String? = config[RULES_CONFIG_URL] as? String
            if (!rulesURL.isNullOrBlank()) {
                configurationRulesManager.applyDownloadedRules(rulesURL, api)
            } else {
                MobileCore.log(
                    LoggingMode.ERROR,
                    TAG,
                    "Cannot load rules form rules URL: $rulesURL}"
                )
            }
        }
    }

    private fun Set<*>.isAllString(): Boolean {
        this.forEach { if (it !is String) return false }
        return true
    }
}
