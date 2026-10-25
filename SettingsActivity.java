package net.xuanzhi.launcher.ui;

import android.os.Bundle;
import android.text.InputType;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;

import net.hanyi.launcher.R;
import net.hanyi.launcher.profile.LaunchProfile;

/**
 * 启动配置页：-Xmx、JVM 参数、渲染后端、游戏目录。
 * 这里直接改「默认 profile」。多 profile 的话把 key 换成 profile.id 前缀即可。
 */
public final class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setSupportActionBar(findViewById(com.google.android.material.R.id.action_bar));
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(R.string.title_settings);
        if (b == null) getSupportFragmentManager().beginTransaction()
                .replace(android.R.id.content, new F()).commit();
    }

    public static class F extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle b, String root) {
            getPreferenceManager().setSharedPreferencesName("profile_default");
            setPreferencesFromResource(R.xml.prefs_launch, root);

            EditTextPreference mem = findPreference("max_mem");
            if (mem != null) {
                mem.setOnBindEditTextListener(e -> e.setInputType(InputType.TYPE_CLASS_NUMBER));
                mem.setSummaryProvider(p -> {
                    String v = ((EditTextPreference) p).getText();
                    return (v == null || v.isEmpty()) ? "自动（按设备内存算）" : v + " MB";
                });
            }
            EditTextPreference jvm = findPreference("jvm_args");
            if (jvm != null) jvm.setSummaryProvider(p -> {
                String v = ((EditTextPreference) p).getText();
                return v == null || v.isEmpty() ? getString(R.string.pref_jvm_args_sum) : v;
            });

            ListPreference r = findPreference("renderer");
            if (r != null) {
                refreshRendererList(r);
                r.setOnPreferenceChangeListener((pref, val) -> { refreshRendererList(r); return true; });
            }
        }

        private void refreshRendererList(ListPreference r) {
            List<net.hanyi.launcher.launch.RendererRegistry.Entry> ls =
                    net.hanyi.launcher.launch.RendererRegistry.list(getContext());
            CharSequence[] labels = new CharSequence[ls.size()];
            CharSequence[] vals = new CharSequence[ls.size()];
            for (int i = 0; i < ls.size(); i++) {
                labels[i] = ls.get(i).name + (ls.get(i).detected ? "   ✓" : "")
                        + "\n" + ls.get(i).hint;
                vals[i] = ls.get(i).id;
            }
            r.setEntries(labels); r.setEntryValues(vals);
            r.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            net.hanyi.launcher.launch.RendererRegistry.invalidate(); // 每次点开都重新探测一次
        }
        }
    }
}
