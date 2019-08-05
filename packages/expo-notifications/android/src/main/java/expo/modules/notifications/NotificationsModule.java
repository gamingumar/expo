// Copyright 2015-present 650 Industries. All rights reserved.

package expo.modules.notifications;

import com.cronutils.model.Cron;

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.support.annotation.NonNull;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.InstanceIdResult;

import org.json.JSONException;
import org.unimodules.core.ExportedModule;
import org.unimodules.core.ModuleRegistry;
import org.unimodules.core.Promise;
import org.unimodules.core.arguments.MapArguments;
import org.unimodules.core.interfaces.ExpoMethod;
import org.unimodules.core.interfaces.RegistryLifecycleListener;
import org.unimodules.core.interfaces.services.EventEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import expo.modules.notifications.channels.ChannelManager;
import expo.modules.notifications.channels.ChannelPOJO;
import expo.modules.notifications.channels.ChannelScopeManager;
import expo.modules.notifications.schedulers.IntervalSchedulerModel;
import expo.modules.notifications.schedulers.SchedulerImpl;
import expo.modules.notifications.postoffice.Mailbox;
import expo.modules.notifications.postoffice.PostOfficeProxy;
import expo.modules.notifications.presenters.NotificationPresenterImpl;
import expo.modules.notifications.presenters.NotificationPresenter;
import expo.modules.notifications.exceptions.UnableToScheduleException;
import expo.modules.notifications.managers.SchedulersManagerProxy;
import expo.modules.notifications.schedulers.CalendarSchedulerModel;

import static expo.modules.notifications.NotificationConstants.NOTIFICATION_CHANNEL_ID;
import static expo.modules.notifications.NotificationConstants.NOTIFICATION_DEFAULT_CHANNEL_ID;
import static expo.modules.notifications.NotificationConstants.NOTIFICATION_DEFAULT_CHANNEL_NAME;
import static expo.modules.notifications.NotificationConstants.NOTIFICATION_EXPERIENCE_ID_KEY;
import static expo.modules.notifications.NotificationConstants.NOTIFICATION_ID_KEY;
import static expo.modules.notifications.helpers.ExpoCronParser.createCronInstance;

public class NotificationsModule extends ExportedModule implements RegistryLifecycleListener, Mailbox {

  private static final String TAG = NotificationsModule.class.getSimpleName();

  private static final String ON_USER_INTERACTION_EVENT = "Exponent.onUserInteraction";
  private static final String ON_FOREGROUND_NOTIFICATION_EVENT = "Exponent.onForegroundNotification";

  private Context mContext;
  private String mExperienceId;
  private ChannelManager mChannelManager;

  private ModuleRegistry mModuleRegistry = null;

  public NotificationsModule(Context context) {
    super(context);
    mContext = context;
  }

  @Override
  public String getName() {
    return "ExponentNotifications";
  }

  @ExpoMethod
  public void createCategoryAsync(final String categoryIdParam, final List<HashMap<String, Object>> actions, final Promise promise) {
    String categoryId = getProperString(categoryIdParam);
    List<Map<String, Object>> newActions = new ArrayList<>();

    for (Object actionObject : actions) {
      if (actionObject instanceof Map) {
        Map<String, Object> action = (Map<String, Object>) actionObject;
        newActions.add(action);
      }
    }

    NotificationActionCenter.putCategory(categoryId, newActions);
    promise.resolve(null);
  }

  @ExpoMethod
  public void deleteCategoryAsync(final String categoryIdParam, final Promise promise) {
    String categoryId = getProperString(categoryIdParam);
    NotificationActionCenter.removeCategory(categoryId);
    promise.resolve(null);
  }

  protected String getProperString(String string) { // scoped version return expId+":"+string;
    return string;
  }

  @ExpoMethod
  public void getDevicePushTokenAsync(final Promise promise) {
    FirebaseInstanceId.getInstance().getInstanceId()
        .addOnCompleteListener(new OnCompleteListener<InstanceIdResult>() {
          @Override
          public void onComplete(@NonNull Task<InstanceIdResult> task) {
            if (!task.isSuccessful()) {
              promise.reject(task.getException());
            }
            String token = task.getResult().getToken();
            promise.resolve(token);
          }
        });
  }

  @ExpoMethod
  public void createChannel(String channelId, final HashMap data, final Promise promise) {
    HashMap channelData = data;
    channelData.put(NOTIFICATION_CHANNEL_ID, channelId);

    ChannelPOJO channelPOJO = ChannelPOJO.createChannelPOJO(channelData);

    mChannelManager.addChannel(channelId, channelPOJO, mContext.getApplicationContext());
    promise.resolve(null);
  }

  @ExpoMethod
  public void deleteChannel(String channelId, final Promise promise) {
    mChannelManager.deleteChannel(channelId, mContext.getApplicationContext());
    promise.resolve(null);
  }

  @ExpoMethod
  public void createChannelGroup(String groupId, String groupName, final Promise promise) {
    groupId = getProperString(groupId);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationManager notificationManager =
          (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
      notificationManager.createNotificationChannelGroup(new NotificationChannelGroup(groupId, groupName));
    }
    promise.resolve(null);
  }

  @ExpoMethod
  public void deleteChannelGroup(String groupId, final Promise promise) {
    groupId = getProperString(groupId);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationManager notificationManager =
          (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
      notificationManager.deleteNotificationChannelGroup(groupId);
    }
    promise.resolve(null);
  }

  @ExpoMethod
  public void presentLocalNotification(final HashMap data, final Promise promise) {
    Bundle bundle = new MapArguments(data).toBundle();
    bundle.putString(NOTIFICATION_EXPERIENCE_ID_KEY, mExperienceId);

    Integer notificationId = Math.abs( new Random().nextInt() );
    bundle.putString(NOTIFICATION_ID_KEY, notificationId.toString());

    NotificationPresenter notificationPresenter = new NotificationPresenterImpl();
    notificationPresenter.presentNotification(
        mContext.getApplicationContext(),
        mExperienceId,
        bundle,
        notificationId
    );

    promise.resolve(notificationId.toString());
  }

  @ExpoMethod
  public void dismissNotification(final String notificationId, final Promise promise) {
    int id = Integer.parseInt(notificationId);
    NotificationManager notificationManager = (NotificationManager) mContext
        .getSystemService(Context.NOTIFICATION_SERVICE);
    notificationManager.cancel(id);
    promise.resolve(null);
  }

  @ExpoMethod
  public void dismissAllNotifications(final Promise promise) {
    NotificationManager notificationManager = (NotificationManager) mContext
        .getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      StatusBarNotification[] activeNotifications = notificationManager.getActiveNotifications();

      for (StatusBarNotification notification : activeNotifications) {
        if (notification.getTag().equals(mExperienceId)) {
          notificationManager.cancel(notification.getId());
        }
      }

      promise.resolve(null);
    } else {
      promise.reject("Function dismissAllNotifications is available from android 6.0");
    }
  }

  @ExpoMethod
  public void cancelScheduledNotificationAsync(final String notificationId, final Promise promise) {
    SchedulersManagerProxy.getInstance(mContext.getApplicationContext()
        .getApplicationContext())
        .removeScheduler(notificationId);

    dismissNotification(notificationId, promise);
  }

  @ExpoMethod
  public void cancelAllScheduledNotificationsAsync(final Promise promise) {
    SchedulersManagerProxy
        .getInstance(mContext.getApplicationContext())
        .removeAll(mExperienceId);

    dismissAllNotifications(promise);
  }

  @ExpoMethod
  public void scheduleNotificationWithTimer(final HashMap<String, Object> data, final HashMap<String, Object> options, final Promise promise) {
    if (data.containsKey("categoryId")) {
      data.put("categoryId", getProperString((String)data.get("categoryId")));
    }
    HashMap<String, Object> details = new HashMap<>();
    details.put("data", data);

    details.put("experienceId", mExperienceId);

    IntervalSchedulerModel intervalSchedulerModel = new IntervalSchedulerModel();
    intervalSchedulerModel.setExperienceId(mExperienceId);
    intervalSchedulerModel.setDetails(details);
    intervalSchedulerModel.setRepeat(options.containsKey("repeat") && (Boolean) options.get("repeat"));
    intervalSchedulerModel.setScheduledTime(System.currentTimeMillis() + ((Double) options.get("interval")).longValue());
    intervalSchedulerModel.setInterval(((Double) options.get("interval")).longValue()); // on iOS we cannot change interval

    SchedulerImpl scheduler = new SchedulerImpl(intervalSchedulerModel);

    SchedulersManagerProxy.getInstance(mContext.getApplicationContext()).addScheduler(
        scheduler,
        (String id) -> {
          if (id == null) {
            promise.reject(new UnableToScheduleException());
            return false;
          }
          promise.resolve(id);
          return true;
        }
    );
  }

  @ExpoMethod
  public void scheduleNotificationWithCalendar(final HashMap options, final HashMap data, final Promise promise) {
    if (data.containsKey("categoryId")) {
      data.put("categoryId", getProperString((String)data.get("categoryId")));
    }
    HashMap<String, Object> details = new HashMap<>();
    details.put("data", data);

    Cron cron = createCronInstance(options);

    CalendarSchedulerModel calendarSchedulerModel = new CalendarSchedulerModel();
    calendarSchedulerModel.setExperienceId(mExperienceId);
    calendarSchedulerModel.setDetails(details);
    calendarSchedulerModel.setRepeat(options.containsKey("repeat") && (Boolean) options.get("repeat"));
    calendarSchedulerModel.setCalendarData(cron.asString());

    SchedulerImpl scheduler = new SchedulerImpl(calendarSchedulerModel);

    SchedulersManagerProxy.getInstance(mContext.getApplicationContext()).addScheduler(
        scheduler,
        (String id) -> {
          if (id == null) {
            promise.reject(new UnableToScheduleException());
            return false;
          }
          promise.resolve(id);
          return true;
        }
    );
  }

  public void onCreate(ModuleRegistry moduleRegistry) {
    mModuleRegistry = moduleRegistry;
    try {
      mExperienceId = mManifest.getString(ExponentManifest.MANIFEST_ID_KEY); // IdProvider
    } catch (JSONException e) {
      e.printStackTrace();
    }

    createDefaultChannel();

    mChannelManager = new ChannelScopeManager(mExperienceId);

    PostOfficeProxy.getInstance().registerModuleAndGetPendingDeliveries(mExperienceId, this);
  }

  private void createDefaultChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      CharSequence name = NOTIFICATION_DEFAULT_CHANNEL_NAME;
      int importance = NotificationManager.IMPORTANCE_DEFAULT;
      NotificationChannel channel = new NotificationChannel(NOTIFICATION_DEFAULT_CHANNEL_ID, name, importance);
      NotificationManager notificationManager = mContext.getSystemService(NotificationManager.class);
      notificationManager.createNotificationChannel(channel);
    }
  }

  public void onDestory() {
    PostOfficeProxy.getInstance().unregisterModule(mExperienceId);
  }

  @Override
  public void onUserInteraction(Bundle userInteraction) {
    EventEmitter eventEmitter = mModuleRegistry.getModule(EventEmitter.class);
    if (eventEmitter != null) {
      eventEmitter.emit(ON_USER_INTERACTION_EVENT, userInteraction);
    }
  }

  @Override
  public void onForegroundNotification(Bundle notification) {
    EventEmitter eventEmitter = mModuleRegistry.getModule(EventEmitter.class);
    if (eventEmitter != null) {
      eventEmitter.emit(ON_FOREGROUND_NOTIFICATION_EVENT, notification);
    }
  }

}
