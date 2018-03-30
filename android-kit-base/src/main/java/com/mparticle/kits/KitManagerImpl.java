package com.mparticle.kits;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.mparticle.AttributionError;
import com.mparticle.AttributionListener;
import com.mparticle.AttributionResult;
import com.mparticle.MPEvent;
import com.mparticle.MParticle;
import com.mparticle.ReferrerReceiver;
import com.mparticle.UserAttributeListener;
import com.mparticle.commerce.CommerceEvent;
import com.mparticle.identity.IdentityStateListener;
import com.mparticle.identity.MParticleUser;
import com.mparticle.internal.AppStateManager;
import com.mparticle.internal.BackgroundTaskHandler;
import com.mparticle.internal.ConfigManager;
import com.mparticle.internal.KitManager;
import com.mparticle.internal.Logger;
import com.mparticle.internal.MPUtility;
import com.mparticle.internal.PushRegistrationHelper;
import com.mparticle.internal.ReportingManager;
import com.mparticle.kits.mappings.CustomMapping;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;


public class KitManagerImpl implements KitManager, AttributionListener, UserAttributeListener {
    private final ReportingManager mReportingManager;
    private final AppStateManager mAppStateManager;
    private final ConfigManager mConfigManager;
    private final BackgroundTaskHandler mBackgroundTaskHandler;
    KitIntegrationFactory mKitIntegrationFactory;

    private static final String RESERVED_KEY_LTV = "$Amount";
    private static final String METHOD_NAME = "$MethodName";
    private static final String LOG_LTV = "LogLTVIncrease";

    private Map<Integer, AttributionResult> mAttributionResultsMap = new TreeMap<>();


    ConcurrentHashMap<Integer, KitIntegration> providers = new ConcurrentHashMap<Integer, KitIntegration>(0);
    private final Context mContext;

    public KitManagerImpl(Context context, ReportingManager reportingManager, ConfigManager configManager, AppStateManager appStateManager, BackgroundTaskHandler backgroundTaskHandler) {
        mContext = context;
        mReportingManager = reportingManager;
        mConfigManager = configManager;
        mAppStateManager = appStateManager;
        mBackgroundTaskHandler = backgroundTaskHandler;
        mKitIntegrationFactory = new KitIntegrationFactory();
        MParticle.getInstance().Identity().addIdentityStateListener(new IdentityStateListener() {
            @Override
            public void onUserIdentified(MParticleUser user) {
                user.getUserAttributes(KitManagerImpl.this);
            }
        });
    }

    /**
     * Need this method so that we can override it during unit tests.
     */
    protected KitConfiguration createKitConfiguration(JSONObject configuration) throws JSONException {
        return KitConfiguration.createKitConfiguration(configuration);
    }

    public void setKitFactory(KitIntegrationFactory kitIntegrationFactory) {
        mKitIntegrationFactory = kitIntegrationFactory;
    }

    @Override
    public void updateKits(final JSONArray kitConfigs) {
        Runnable runnable = new UpdateKitRunnable(kitConfigs);
        if (Looper.getMainLooper() != Looper.myLooper()) {
            new Handler(Looper.getMainLooper()).post(runnable);
        }else{
            runnable.run();
        }
    }

    private ReportingManager getReportingManager() {
        return mReportingManager;
    }

    public boolean isBackgrounded() {
        return mAppStateManager.isBackgrounded();
    }

    public int getUserBucket() {
        return mConfigManager.getUserBucket();
    }

    public boolean isOptedOut() {
        return !mConfigManager.isEnabled();
    }

    void setIntegrationAttributes(KitIntegration kitIntegration, Map<String, String> integrationAttributes) {
        mConfigManager.setIntegrationAttributes(kitIntegration.getConfiguration().getKitId(), integrationAttributes);
    }

    Map<String, String> getIntegrationAttributes(KitIntegration kitIntegration) {
        return mConfigManager.getIntegrationAttributes(kitIntegration.getConfiguration().getKitId());
    }

    void clearIntegrationAttributes(KitIntegration kitIntegration) {
        setIntegrationAttributes(kitIntegration, null);
    }

    class UpdateKitRunnable implements Runnable {

        private final JSONArray kitConfigs;

        public UpdateKitRunnable(JSONArray kitConfigs) {
            super();
            this.kitConfigs = kitConfigs;
        }

        @Override
        public void run() {
            PushRegistrationHelper.PushRegistration pushRegistration = PushRegistrationHelper.getLatestPushRegistration(getContext());
            Intent mockInstallReferrer = ReferrerReceiver.getMockInstallReferrerIntent(MParticle.getInstance().getInstallReferrer());
            int currentId = 0;
            HashSet<Integer> activeIds = new HashSet<Integer>();
            if (kitConfigs != null) {
                for (int i = 0; i < kitConfigs.length(); i++) {
                    try {
                        JSONObject current = kitConfigs.getJSONObject(i);
                        currentId = current.getInt(KitConfiguration.KEY_ID);
                        activeIds.add(currentId);
                        if (mKitIntegrationFactory.isSupported(currentId)) {
                            KitConfiguration configuration = createKitConfiguration(current);
                            if (providers.containsKey(currentId)) {
                                providers.get(currentId).setConfiguration(configuration);
                                providers.get(currentId).onSettingsUpdated(configuration.getSettings());
                            }else {
                                KitIntegration provider = mKitIntegrationFactory.createInstance(KitManagerImpl.this, configuration);
                                providers.put(currentId, provider);
                                if (provider.isDisabled()) {
                                    continue;
                                }
                                provider.onKitCreate(configuration.getSettings(), getContext());
                                Logger.debug("OnKitCreate called: " + provider.getName());
                                if (provider instanceof KitIntegration.ActivityListener) {
                                    WeakReference<Activity> activityWeakReference = getCurrentActivity();
                                    if (activityWeakReference != null) {
                                        Activity activity = activityWeakReference.get();
                                        if (activity != null) {
                                            KitIntegration.ActivityListener listener = (KitIntegration.ActivityListener)provider;
                                            getReportingManager().logAll(
                                                    listener.onActivityCreated(activity, null)
                                            );
                                            getReportingManager().logAll(
                                                    listener.onActivityStarted(activity)
                                            );
                                            getReportingManager().logAll(
                                                    listener.onActivityResumed(activity)
                                            );
                                        }
                                    }
                                }

                                Intent intent = new Intent(MParticle.ServiceProviders.BROADCAST_ACTIVE + currentId);
                                getContext().sendBroadcast(intent);

                                if (provider instanceof KitIntegration.AttributeListener) {
                                    syncUserIdentities((KitIntegration.AttributeListener) provider, provider.getConfiguration());
                                }

                                if (mockInstallReferrer != null) {
                                    provider.setInstallReferrer(mockInstallReferrer);
                                }

                                if (pushRegistration != null && !MPUtility.isEmpty(pushRegistration.instanceId) && provider instanceof KitIntegration.PushListener) {
                                    if (((KitIntegration.PushListener) provider).onPushRegistration(pushRegistration.instanceId, pushRegistration.senderId)) {
                                        ReportingMessage message = ReportingMessage.fromPushRegistrationMessage(provider);
                                        getReportingManager().log(message);
                                    }
                                }
                            }


                        }
                    } catch (JSONException jse) {
                        Logger.error("Exception while parsing configuration for id " + currentId + ": " + jse.getMessage());
                    } catch (Exception e) {
                        Logger.error("Exception while starting kit id " + currentId + ": " + e.getMessage());
                    }
                }
            }

            Iterator<Integer> ids = providers.keySet().iterator();
            while (ids.hasNext()) {
                Integer id = ids.next();
                if (!activeIds.contains(id)) {
                    KitIntegration integration = providers.get(id);
                    if (integration != null) {
                        clearIntegrationAttributes(integration);
                        integration.onKitDestroy();
                        integration.onKitCleanup();
                    }
                    ids.remove();
                    Intent intent = new Intent(MParticle.ServiceProviders.BROADCAST_DISABLED + id);
                    getContext().sendBroadcast(intent);
                }
            }
            MParticle.getInstance().getKitManager().replayAndDisableQueue();
        }
    }

    @Override
    public String getActiveModuleIds() {
        if (providers.isEmpty()) {
            return "";
        } else {
            Set keys = providers.keySet();
            StringBuilder buffer = new StringBuilder(keys.size() * 3);

            Iterator<Integer> it = keys.iterator();
            while (it.hasNext()) {
                Integer next = it.next();
                if (providers.get(next) != null && !providers.get(next).isDisabled()) {
                    buffer.append(next);
                    if (it.hasNext()) {
                        buffer.append(",");
                    }
                }
            }
            return buffer.toString();
        }
    }

    @Override
    public boolean isKitActive(int serviceProviderId) {
        KitIntegration provider = providers.get(serviceProviderId);
        return provider != null && !provider.isDisabled();
    }

    @Override
    public Object getKitInstance(int kitId) {
        KitIntegration kit = providers.get(kitId);
        return kit == null ? null : kit.getInstance();
    }

    //================================================================================
    // General KitIntegration forwarding
    //================================================================================

    @Override
    public void setLocation(Location location) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (!provider.isDisabled()) {
                    provider.setLocation(location);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call setLocation for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void logNetworkPerformance(String url, long startTime, String method, long length, long bytesSent, long bytesReceived, String requestString, int responseCode) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (!provider.isDisabled()) {
                    List<ReportingMessage> report = provider.logNetworkPerformance(url, startTime, method, length, bytesSent, bytesReceived, requestString, responseCode);
                    getReportingManager().logAll(report);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call logNetworkPerformance for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public Uri getSurveyUrl(int serviceId, Map<String, String> userAttributes, Map<String, List<String>> userAttributeLists) {
        KitIntegration provider = providers.get(serviceId);
        if (provider != null){
            return provider.getSurveyUrl((Map<String, String>)provider.getConfiguration().filterAttributes(provider.getConfiguration().getUserAttributeFilters(), userAttributes),
                    (Map<String, List<String>>)provider.getConfiguration().filterAttributes(provider.getConfiguration().getUserAttributeFilters(), userAttributeLists));
        } else {
            return null;
        }
    }

    @Override
    public void setOptOut(boolean optOutStatus) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (!provider.isDisabled()) {
                    List<ReportingMessage> messages = provider.setOptOut(optOutStatus);
                    getReportingManager().logAll(messages);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call setOptOut for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public Set<Integer> getSupportedKits() {
        return mKitIntegrationFactory.getSupportedKits();
    }

    //================================================================================
    // KitIntegration.CommerceListener forwarding
    //================================================================================

    @Override
    public void logCommerceEvent(CommerceEvent event) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (!provider.isDisabled()) {
                    CommerceEvent filteredEvent = provider.getConfiguration().filterCommerceEvent(event);
                    if (filteredEvent != null) {
                        if (provider instanceof KitIntegration.CommerceListener) {
                            List<CustomMapping.ProjectionResult> projectedEvents = CustomMapping.projectEvents(
                                    filteredEvent,
                                    provider.getConfiguration().getCustomMappingList(),
                                    provider.getConfiguration().getDefaultCommerceCustomMapping()
                            );
                            if (projectedEvents != null && projectedEvents.size() > 0) {
                                ReportingMessage masterMessage = ReportingMessage.fromEvent(provider, filteredEvent);
                                boolean forwarded = false;
                                for (int i = 0; i < projectedEvents.size(); i++) {
                                    CustomMapping.ProjectionResult result = projectedEvents.get(i);
                                    List<ReportingMessage> report = null;
                                    String messageType = null;
                                    if (result.getMPEvent() != null) {
                                        report = ((KitIntegration.EventListener) provider).logEvent(projectedEvents.get(i).getMPEvent());
                                        messageType = ReportingMessage.MessageType.EVENT;
                                    } else {
                                        report =((KitIntegration.CommerceListener) provider).logEvent(projectedEvents.get(i).getCommerceEvent());
                                        messageType = ReportingMessage.MessageType.COMMERCE_EVENT;
                                    }
                                    if (report != null && report.size() > 0) {
                                        forwarded = true;
                                        for (ReportingMessage message : report) {
                                            masterMessage.addProjectionReport(
                                                    new ReportingMessage.ProjectionReport(projectedEvents.get(i).getProjectionId(),
                                                            messageType,
                                                            message.getEventName(),
                                                            message.getEventTypeString())
                                            );
                                        }
                                    }
                                }
                                if (forwarded) {
                                    getReportingManager().log(masterMessage);
                                }
                            } else {
                                List<ReportingMessage> reporting = ((KitIntegration.CommerceListener) provider).logEvent(filteredEvent);
                                if (reporting != null && reporting.size() > 0) {
                                    getReportingManager().log(
                                            ReportingMessage.fromEvent(provider, filteredEvent)
                                    );
                                }
                            }
                        } else if (provider instanceof KitIntegration.EventListener){
                            List<MPEvent> events = CommerceEventUtils.expand(filteredEvent);
                            boolean forwarded = false;
                            if (events != null) {
                                for (int i = 0; i < events.size(); i++) {
                                    List<ReportingMessage> reporting = ((KitIntegration.EventListener) provider).logEvent(events.get(i));
                                    forwarded = forwarded || (reporting != null && reporting.size() > 0);
                                }
                            }
                            if (forwarded) {
                                getReportingManager().log(
                                        ReportingMessage.fromEvent(provider, filteredEvent)
                                );
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Logger.warning("Failed to call logCommerceEvent for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    //================================================================================
    // KitIntegration.PushListener forwarding
    //================================================================================

    @Override
    public boolean onMessageReceived(Context context, Intent intent) {
        for (KitIntegration provider : providers.values()) {
            if (provider instanceof KitIntegration.PushListener) {
                try {
                    if (!provider.isDisabled() && ((KitIntegration.PushListener) provider).willHandlePushMessage(intent)) {
                        ((KitIntegration.PushListener) provider).onPushMessageReceived(context, intent);
                        ReportingMessage message = ReportingMessage.fromPushMessage(provider, intent);
                        getReportingManager().log(message);
                        return true;
                    }
                } catch (Exception e) {
                    Logger.warning("Failed to call onPushMessageReceived for kit: " + provider.getName() + ": " + e.getMessage());
                }
            }
        }
        return false;
    }

    @Override
    public boolean onPushRegistration(String token, String senderId) {
        for (KitIntegration provider : providers.values()) {
            if (provider instanceof KitIntegration.PushListener) {
                try {
                    if (!provider.isDisabled()) {
                        if (((KitIntegration.PushListener) provider).onPushRegistration(token, senderId)) {
                            ReportingMessage message = ReportingMessage.fromPushRegistrationMessage(provider);
                            getReportingManager().log(message);
                        }
                        return true;
                    }
                } catch (Exception e) {
                    Logger.warning("Failed to call onPushRegistration for kit: " + provider.getName() + ": " + e.getMessage());
                }
            }
        }
        return false;
    }

    //================================================================================
    // KitIntegration.AttributeListener forwarding
    //================================================================================
    @Override
    public void onUserAttributesReceived(Map<String, String> userAttributes, Map<String, List<String>> userAttributeLists) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.AttributeListener && !provider.isDisabled()) {
                    Map<String, String> filteredAttributeSingles = (Map<String, String>)KitConfiguration.filterAttributes(provider.getConfiguration().getUserAttributeFilters(),
                            userAttributes);
                    Map<String, List<String>> filteredAttributeLists = (Map<String, List<String>>)KitConfiguration.filterAttributes(provider.getConfiguration().getUserAttributeFilters(),
                            userAttributeLists);
                    if (!((KitIntegration.AttributeListener) provider).supportsAttributeLists()){
                        for (Map.Entry<String, List<String>> entry : userAttributeLists.entrySet()) {
                            filteredAttributeSingles.put(entry.getKey(), KitUtils.join(entry.getValue()));
                        }
                        filteredAttributeLists.clear();
                    }
                    ((KitIntegration.AttributeListener)provider).setAllUserAttributes(filteredAttributeSingles, filteredAttributeLists);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call setUserAttributes for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    private void syncUserIdentities(KitIntegration.AttributeListener attributeListener, KitConfiguration configuration) {
        MParticleUser user = MParticle.getInstance().Identity().getCurrentUser();
        if (user != null) {
            Map<MParticle.IdentityType, String> identities = user.getUserIdentities();
            if (identities != null) {
                for (Map.Entry<MParticle.IdentityType, String> entry : identities.entrySet()) {
                    if (configuration.shouldSetIdentity(entry.getKey())) {
                        attributeListener.setUserIdentity(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
    }

    @Override
    public void setUserAttribute(String attributeKey, String attributeValue) {
        for (KitIntegration provider : providers.values()) {
            try {
                setUserAttribute(provider, attributeKey, attributeValue);
            } catch (Exception e) {
                Logger.warning("Failed to call setUserAttributes for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void setUserAttributeList(String attributeKey, List<String> valuesList) {
        for (KitIntegration provider : providers.values()) {
            try {
                setUserAttribute(provider, attributeKey, valuesList);
            } catch (Exception e) {
                Logger.warning("Failed to call setUserAttributes for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    private void setUserAttribute(KitIntegration provider, String attributeKey, List<String> valueList) {
        if (provider instanceof KitIntegration.AttributeListener && !provider.isDisabled() &&
                KitConfiguration.shouldForwardAttribute(provider.getConfiguration().getUserAttributeFilters(),
                        attributeKey)) {
            if (((KitIntegration.AttributeListener)provider).supportsAttributeLists()) {
                ((KitIntegration.AttributeListener) provider).setUserAttributeList(attributeKey, valueList);
            } else {
                ((KitIntegration.AttributeListener)provider).setUserAttribute(attributeKey, KitUtils.join(valueList));
            }
        }
    }

    private void setUserAttribute(KitIntegration provider, String attributeKey, String attributeValue) {
        if (provider instanceof KitIntegration.AttributeListener && !provider.isDisabled() &&
                KitConfiguration.shouldForwardAttribute(provider.getConfiguration().getUserAttributeFilters(),
                        attributeKey)) {
            ((KitIntegration.AttributeListener)provider).setUserAttribute(attributeKey, attributeValue);
        }
    }

    @Override
    public void removeUserAttribute(String key) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.AttributeListener && !provider.isDisabled()) {
                    ((KitIntegration.AttributeListener)provider).removeUserAttribute(key);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call removeUserAttribute for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void setUserIdentity(String id, MParticle.IdentityType identityType) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.AttributeListener && !provider.isDisabled() && provider.getConfiguration().shouldSetIdentity(identityType)) {
                    ((KitIntegration.AttributeListener)provider).setUserIdentity(identityType, id);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call setUserIdentity for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void removeUserIdentity(MParticle.IdentityType identityType) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.AttributeListener && !provider.isDisabled()) {
                    ((KitIntegration.AttributeListener)provider).removeUserIdentity(identityType);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call removeUserIdentity for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void logout() {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.AttributeListener && !provider.isDisabled()) {
                    List<ReportingMessage> report = ((KitIntegration.AttributeListener)provider).logout();
                    getReportingManager().logAll(report);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call logout for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    //================================================================================
    // KitIntegration.EventListener forwarding
    //================================================================================

    public Context getContext() {
        return this.mContext;
    }

    @Override
    public WeakReference<Activity> getCurrentActivity() {
        return mAppStateManager.getCurrentActivity();
    }

    @Override
    public void logEvent(MPEvent event) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.EventListener && !provider.isDisabled() && provider.getConfiguration().shouldLogEvent(event)) {
                    MPEvent eventCopy = new MPEvent(event);
                    eventCopy.setInfo(
                            provider.getConfiguration().filterEventAttributes(eventCopy)
                    );
                    List<CustomMapping.ProjectionResult> projectedEvents = CustomMapping.projectEvents(
                            eventCopy,
                            provider.getConfiguration().getCustomMappingList(),
                            provider.getConfiguration().getDefaultEventProjection()
                    );
                    List<ReportingMessage> reportingMessages = new LinkedList<ReportingMessage>();
                    if (projectedEvents == null) {
                        List<ReportingMessage> messages = null;
                        if (eventCopy.getInfo() != null
                                && eventCopy.getInfo().containsKey(METHOD_NAME)
                                && eventCopy.getInfo().get(METHOD_NAME).equals(LOG_LTV)){
                            messages = ((KitIntegration.CommerceListener) provider).logLtvIncrease(
                                    new BigDecimal(eventCopy.getInfo().get(RESERVED_KEY_LTV)),
                                    new BigDecimal(eventCopy.getInfo().get(RESERVED_KEY_LTV)),
                                    eventCopy.getEventName(),
                                    eventCopy.getInfo());
                        }else {
                            messages = ((KitIntegration.EventListener) provider).logEvent(eventCopy);
                        }
                        if (messages != null && messages.size() > 0) {
                            reportingMessages.addAll(messages);
                        }
                    } else {
                        ReportingMessage masterMessage = ReportingMessage.fromEvent(provider, eventCopy);
                        boolean forwarded = false;
                        for (int i = 0; i < projectedEvents.size(); i++) {
                            List<ReportingMessage> messages = ((KitIntegration.EventListener) provider).logEvent(projectedEvents.get(i).getMPEvent());
                            if (messages != null && messages.size() > 0) {
                                forwarded = true;
                                for (ReportingMessage message : messages) {
                                    ReportingMessage.ProjectionReport report = new ReportingMessage.ProjectionReport(
                                            projectedEvents.get(i).getProjectionId(),
                                            ReportingMessage.MessageType.EVENT,
                                            message.getEventName(),
                                            message.getEventTypeString()
                                    );
                                    masterMessage.addProjectionReport(report);
                                }

                            }
                        }
                        if (forwarded) {
                            reportingMessages.add(masterMessage);
                        }
                    }
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call logEvent for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void leaveBreadcrumb(String breadcrumb) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.EventListener && !provider.isDisabled()) {
                    List<ReportingMessage> report = ((KitIntegration.EventListener)provider).leaveBreadcrumb(breadcrumb);
                    getReportingManager().logAll(report);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call leaveBreadcrumb for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void logError(String message, Map<String, String> eventData) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.EventListener && !provider.isDisabled()) {
                    List<ReportingMessage> report = ((KitIntegration.EventListener)provider).logError(message, eventData);
                    getReportingManager().logAll(report);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call logError for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void logException(Exception exception, Map<String, String> eventData, String message) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.EventListener && !provider.isDisabled()) {
                    List<ReportingMessage> report = ((KitIntegration.EventListener)provider).logException(exception, eventData, message);
                    getReportingManager().logAll(report);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call logException for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void logScreen(MPEvent screenEvent) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.EventListener && !provider.isDisabled() && provider.getConfiguration().shouldLogScreen(screenEvent.getEventName())) {
                    MPEvent filteredEvent = new MPEvent.Builder(screenEvent)
                            .info(provider.getConfiguration().filterScreenAttributes(null, screenEvent.getEventName(), screenEvent.getInfo()))
                            .build();

                    List<CustomMapping.ProjectionResult> projectedEvents = CustomMapping.projectEvents(
                            filteredEvent,
                            true,
                            provider.getConfiguration().getCustomMappingList(),
                            provider.getConfiguration().getDefaultEventProjection(),
                            provider.getConfiguration().getDefaultScreenCustomMapping());
                    if (projectedEvents == null) {
                        List<ReportingMessage> report = ((KitIntegration.EventListener) provider).logScreen(filteredEvent.getEventName(), filteredEvent.getInfo());
                        if (report != null && report.size() > 0) {
                            for (ReportingMessage message : report) {
                                message.setMessageType(ReportingMessage.MessageType.SCREEN_VIEW);
                                message.setScreenName(filteredEvent.getEventName());
                            }
                        }
                        getReportingManager().logAll(report);
                    } else {
                        ReportingMessage masterMessage = new ReportingMessage(provider,
                                ReportingMessage.MessageType.SCREEN_VIEW,
                                System.currentTimeMillis(),
                                filteredEvent.getInfo());
                        boolean forwarded = false;
                        for (int i = 0; i < projectedEvents.size(); i++) {
                            List<ReportingMessage> report = ((KitIntegration.EventListener) provider).logEvent(projectedEvents.get(i).getMPEvent());
                            if (report != null && report.size() > 0) {
                                forwarded = true;
                                for (ReportingMessage message : report) {
                                    ReportingMessage.ProjectionReport projectionReport = new ReportingMessage.ProjectionReport(
                                            projectedEvents.get(i).getProjectionId(),
                                            ReportingMessage.MessageType.EVENT,
                                            message.getEventName(),
                                            message.getEventTypeString()
                                    );
                                    masterMessage.addProjectionReport(projectionReport);
                                }
                            }
                        }
                        if (forwarded) {
                            getReportingManager().log(masterMessage);
                        }
                    }
                }
            } catch (Exception e) {
                Logger.warning("Failed to call logScreen for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    //================================================================================
    // KitIntegration.ActivityListener forwarding
    //================================================================================

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.ActivityListener && !provider.isDisabled()) {
                    List<ReportingMessage> reportingMessages = ((KitIntegration.ActivityListener)provider).onActivityCreated(activity, savedInstanceState);
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call onActivityCreated for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onActivityStarted(Activity activity) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.ActivityListener && !provider.isDisabled()) {
                    List<ReportingMessage> reportingMessages = ((KitIntegration.ActivityListener)provider).onActivityStarted(activity);
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call onActivityStarted for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.ActivityListener && !provider.isDisabled()) {
                    List<ReportingMessage> reportingMessages = ((KitIntegration.ActivityListener)provider).onActivityResumed(activity);
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call onActivityResumed for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.ActivityListener && !provider.isDisabled()) {
                    List<ReportingMessage> reportingMessages = ((KitIntegration.ActivityListener)provider).onActivityPaused(activity);
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call onResume for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.ActivityListener && !provider.isDisabled()) {
                    List<ReportingMessage> reportingMessages = ((KitIntegration.ActivityListener)provider).onActivityStopped(activity);
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call onActivityStopped for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.ActivityListener && !provider.isDisabled()) {
                    List<ReportingMessage> reportingMessages = ((KitIntegration.ActivityListener)provider).onActivitySaveInstanceState(activity, outState);
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call onActivitySaveInstanceState for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.ActivityListener && !provider.isDisabled()) {
                    List<ReportingMessage> reportingMessages = ((KitIntegration.ActivityListener)provider).onActivityDestroyed(activity);
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call onActivityDestroyed for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onSessionEnd() {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.SessionListener && !provider.isDisabled()) {
                    List<ReportingMessage> reportingMessages = ((KitIntegration.SessionListener)provider).onSessionEnd();
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call onSessionEnd for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onSessionStart() {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.SessionListener && !provider.isDisabled()) {
                    List<ReportingMessage> reportingMessages = ((KitIntegration.SessionListener)provider).onSessionStart();
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                Logger.warning("Failed to call onSessionStart for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onApplicationForeground() {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.ApplicationStateListener) {
                    ((KitIntegration.ApplicationStateListener)provider).onApplicationForeground();
                }
            } catch (Exception e) {
                Logger.warning("Failed to call onApplicationForeground for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onApplicationBackground() {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.ApplicationStateListener) {
                    ((KitIntegration.ApplicationStateListener)provider).onApplicationBackground();
                }
            } catch (Exception e) {
                Logger.warning("Failed to call onApplicationBackground for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public Map<Integer, AttributionResult> getAttributionResults() {
        return mAttributionResultsMap;
    }

    //================================================================================
    // AttributionListener forwarding
    //================================================================================

    @Override
    public void onResult(AttributionResult result) {
        mAttributionResultsMap.put(result.getServiceProviderId(), result);
        AttributionListener listener = MParticle.getInstance().getAttributionListener();
        if (listener != null && result != null) {
            Logger.debug("Attribution result returned: \n" + result.toString());
            listener.onResult(result);
        }
    }

    @Override
    public void onError(AttributionError error) {
        AttributionListener listener = MParticle.getInstance().getAttributionListener();
        if (listener != null && error != null) {
            Logger.debug("Attribution error returned: \n" + error.toString());
            listener.onError(error);
        }
    }

    @Override
    public void installReferrerUpdated() {
        Intent mockIntent = ReferrerReceiver.getMockInstallReferrerIntent(MParticle.getInstance().getInstallReferrer());
        for (KitIntegration provider: providers.values()) {
            try {
                if (!provider.isDisabled()) {
                    provider.setInstallReferrer(mockIntent);
                }
            }
            catch (Exception e) {
                Logger.warning("Failed to update Install Referrer for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    public void executeNetworkRequest(Runnable runnable) {
        mBackgroundTaskHandler.executeNetworkRequest(runnable);
    }

}
