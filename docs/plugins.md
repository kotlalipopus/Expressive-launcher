# Expressive Launcher plugins

A v1 plugin is a separate Android APK that adds one or more actions to **Settings → Plugins**.

## Install and use

1. Install the plugin APK on your device.
2. Open **Settings → Plugins** and refresh the list if necessary.
3. Check the action name and package, enable the action, and select **Open**.
4. Disable an action to revoke permission to launch it. Uninstall its app through Android settings to remove it completely.

Approval is saved for a specific component and its current APK signing certificates. If the signature changes, enable the action again. Reinstalling a component with the same signature retains its approval.

## Create a plugin

Create a separate Android project with its own application ID, such as `org.example.launcherplugin`. Set `minSdk = 27` to support every Android version supported by the launcher.

Declare an activity inside the plugin manifest's `<application>` element:

```xml
<activity
    android:name=".PluginActivity"
    android:exported="true"
    android:label="@string/plugin_action_label">
    <intent-filter>
        <action android:name="com.example.expressivelauncher.action.PLUGIN" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
    <meta-data
        android:name="com.example.expressivelauncher.plugin.API_VERSION"
        android:value="1" />
</activity>
```

Define `plugin_action_label` in your resources and create `PluginActivity` in your app package. For example:

```java
package org.example.launcherplugin;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class PluginActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView message = new TextView(this);
        message.setGravity(android.view.Gravity.CENTER);
        message.setText("Plugin action is running");
        setContentView(message);
    }
}
```

Declare each additional action as a separate activity with its own label and the same contract. Build and install the APK, then check that each action is disabled by default and opens after you enable it.

## API v1 contract

- The launcher discovers available exported activities with the action above and integer version `1` in the activity's metadata. The activity must not require a permission from the calling app.
- The launcher starts an enabled action using an explicit intent addressed to its component. The intent contains the action string and no extras, URI, or access grants.
- Android runs the plugin under its own application identity. The plugin manages its own interface, permissions, and data. Other Android apps can invoke exported activities, so confirm sensitive operations within the plugin.
- Install the plugin in the same Android profile as the launcher.
- API v1 supports actions. Use a standard Android App Widget for an interactive home-screen widget.
