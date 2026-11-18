# Плагины Expressive Launcher

Плагин v1 — отдельный Android APK, который может добавить одно или несколько действий в раздел **Настройки → Плагины**.

## Установка и использование

1. Установите APK плагина обычным способом.
2. Откройте **Настройки → Плагины**. Если список уже был открыт, нажмите **Обновить список плагинов**.
3. Проверьте название и имя пакета разработчика. Включите нужное действие и нажмите **Открыть**.
4. Чтобы отозвать разрешение на запуск, выключите действие. Для полного удаления воспользуйтесь настройками Android.

Лаунчер сохраняет разрешение для конкретного компонента и текущих сертификатов подписи APK. Если подпись изменится, действие нужно включить заново. Повторная установка компонента с той же подписью сохраняет прежнее разрешение.

## Создание плагина

Создайте отдельный Android-проект с собственным идентификатором пакета, например `org.example.launcherplugin`. Используйте Android SDK и обычный класс `Activity`. Для работы на всех версиях Android, поддерживаемых лаунчером, задайте `minSdk = 27`.

Добавьте в `<application>` манифеста плагина следующую декларацию.

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

Задайте `plugin_action_label` в ресурсах и создайте `PluginActivity` в пакете приложения. Например, для Java:

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

Каждое дополнительное действие объявляйте отдельной Activity с собственной меткой и тем же контрактом. Соберите и установите APK, откройте страницу плагинов и проверьте, что каждое действие выключено по умолчанию и открывается после включения.

## Контракт v1

- Лаунчер ищет Activity с указанным действием и целочисленным значением версии `1` в метаданных самой Activity. Для обнаружения Activity должна быть доступна, экспортирована и не требовать разрешения от вызывающего приложения.
- Лаунчер запускает включённое действие явным Intent с указанным компонентом. В Intent передаётся только строка действия, без extras, URI и разрешений на доступ.
- Android выполняет код плагина в контексте его собственного приложения. Плагин отвечает за свой интерфейс, разрешения и данные. Экспортированную Activity могут вызвать другие Android-приложения, поэтому подтверждайте чувствительные операции внутри плагина.
- При использовании другого профиля Android установите плагин в том же профиле, что и лаунчер.
- API v1 предназначен для действий. Для размещения интерактивного блока на рабочем столе используйте стандартный Android App Widget.
