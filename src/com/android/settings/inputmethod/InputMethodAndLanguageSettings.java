/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2017 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.inputmethod;

import android.app.Activity;
import android.app.Fragment;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.hardware.input.InputDeviceIdentifier;
import android.hardware.input.InputManager;
import android.hardware.input.KeyboardLayout;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.provider.Settings;
import android.provider.Settings.System;
import android.speech.tts.TtsEngines;
import android.support.v14.preference.SwitchPreference;
import android.support.v7.preference.Preference;
import android.support.v7.preference.Preference.OnPreferenceClickListener;
import android.support.v7.preference.PreferenceCategory;
import android.support.v7.preference.PreferenceManager;
import android.support.v7.preference.PreferenceScreen;
import android.text.TextUtils;
import android.view.InputDevice;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.view.textservice.SpellCheckerInfo;
import android.view.textservice.TextServicesManager;

import com.android.internal.app.LocaleHelper;
import com.android.internal.app.LocalePicker;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.Settings.KeyboardLayoutPickerActivity;
import com.android.settings.SettingsActivity;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.SubSettings;
import com.android.settings.UserDictionarySettings;
import com.android.settings.Utils;
import com.android.settings.VoiceInputOutputSettings;
import com.android.settings.dashboard.SummaryLoader;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.search.Indexable;
import com.android.settings.search.SearchIndexableRaw;

import mokee.hardware.MKHardwareManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

public class InputMethodAndLanguageSettings extends SettingsPreferenceFragment
        implements InputManager.InputDeviceListener,
        KeyboardLayoutDialogFragment.OnSetupKeyboardLayoutsListener, Indexable,
        InputMethodPreference.OnSavePreferenceListener {
    private static final String KEY_SPELL_CHECKERS = "spellcheckers_settings";
    private static final String KEY_PHONE_LANGUAGE = "phone_language";
    private static final String KEY_CURRENT_INPUT_METHOD = "current_input_method";
    private static final String KEY_USER_DICTIONARY_SETTINGS = "key_user_dictionary_settings";
    private static final String KEY_PREVIOUSLY_ENABLED_SUBTYPES = "previously_enabled_subtypes";
    private static final String KEY_PHYSICAL_KEYBOARD = "physical_keyboard";

    private PreferenceCategory mGameControllerCategory;
    private Preference mLanguagePref;
    private InputManager mIm;
    private InputMethodManager mImm;
    private boolean mShowsOnlyFullImeAndKeyboardList;
    private Handler mHandler;
    private SettingsObserver mSettingsObserver;
    private Intent mIntentWaitingForResult;
    private InputMethodSettingValuesWrapper mInputMethodSettingValues;

    private PreferenceScreen mPhysicalKeyboard;

    @Override
    protected int getMetricsCategory() {
        return MetricsEvent.INPUTMETHOD_LANGUAGE;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.language_settings);

        final Activity activity = getActivity();
        mImm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        mInputMethodSettingValues = InputMethodSettingValuesWrapper.getInstance(activity);

        if (activity.getAssets().getLocales().length == 1) {
            // No "Select language" pref if there's only one system locale available.
            getPreferenceScreen().removePreference(findPreference(KEY_PHONE_LANGUAGE));
        } else {
            mLanguagePref = findPreference(KEY_PHONE_LANGUAGE);
        }

        new VoiceInputOutputSettings(this).onCreate();

        // Get references to dynamically constructed categories.
        mGameControllerCategory = (PreferenceCategory)findPreference(
                "game_controller_settings_category");

        final Intent startingIntent = activity.getIntent();
        // Filter out irrelevant features if invoked from IME settings button.
        mShowsOnlyFullImeAndKeyboardList = Settings.ACTION_INPUT_METHOD_SETTINGS.equals(
                startingIntent.getAction());
        if (mShowsOnlyFullImeAndKeyboardList) {
            getPreferenceScreen().removeAll();
        }

        // Build game controller preference categories.
        mIm = (InputManager)activity.getSystemService(Context.INPUT_SERVICE);
        updateGameControllers();

        // Spell Checker
        final Preference spellChecker = findPreference(KEY_SPELL_CHECKERS);
        if (spellChecker != null) {
            // Note: KEY_SPELL_CHECKERS preference is marked as persistent="false" in XML.
            InputMethodAndSubtypeUtil.removeUnnecessaryNonPersistentPreference(spellChecker);
            final Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClass(activity, SubSettings.class);
            intent.putExtra(SettingsActivity.EXTRA_SHOW_FRAGMENT,
                    SpellCheckersSettings.class.getName());
            intent.putExtra(SettingsActivity.EXTRA_SHOW_FRAGMENT_TITLE_RESID,
                    R.string.spellcheckers_settings_title);
            spellChecker.setIntent(intent);
        }

        mHandler = new Handler();
        mSettingsObserver = new SettingsObserver(mHandler, activity);

        // If we've launched from the keyboard layout notification, go ahead and just show the
        // keyboard layout dialog.
        final InputDeviceIdentifier identifier =
                startingIntent.getParcelableExtra(Settings.EXTRA_INPUT_DEVICE_IDENTIFIER);
        if (mShowsOnlyFullImeAndKeyboardList && identifier != null) {
            showKeyboardLayoutDialog(identifier);
        }
        updateCurrentImeName();

        mPhysicalKeyboard = (PreferenceScreen) findPreference(KEY_PHYSICAL_KEYBOARD);
        if (mPhysicalKeyboard != null) {
            mPhysicalKeyboard.setVisible(hasHardwareKeyboard());
        }
    }

    private boolean hasHardwareKeyboard() {
        return getResources().getConfiguration().keyboard != Configuration.KEYBOARD_NOKEYS;
    }

    private void updateUserDictionaryPreference(Preference userDictionaryPreference) {
        if (userDictionaryPreference == null) {
            return;
        }
        final Activity activity = getActivity();
        final TreeSet<String> localeSet = UserDictionaryList.getUserDictionaryLocalesSet(activity);
        if (null == localeSet) {
            // The locale list is null if and only if the user dictionary service is
            // not present or disabled. In this case we need to remove the preference.
            getPreferenceScreen().removePreference(userDictionaryPreference);
        } else {
            userDictionaryPreference.setOnPreferenceClickListener(
                    new OnPreferenceClickListener() {
                        @Override
                        public boolean onPreferenceClick(Preference arg0) {
                            // Redirect to UserDictionarySettings if the user needs only one
                            // language.
                            final Bundle extras = new Bundle();
                            final Class<? extends Fragment> targetFragment;
                            if (localeSet.size() <= 1) {
                                if (!localeSet.isEmpty()) {
                                    // If the size of localeList is 0, we don't set the locale
                                    // parameter in the extras. This will be interpreted by the
                                    // UserDictionarySettings class as meaning
                                    // "the current locale". Note that with the current code for
                                    // UserDictionaryList#getUserDictionaryLocalesSet()
                                    // the locale list always has at least one element, since it
                                    // always includes the current locale explicitly.
                                    // @see UserDictionaryList.getUserDictionaryLocalesSet().
                                    extras.putString("locale", localeSet.first());
                                }
                                targetFragment = UserDictionarySettings.class;
                            } else {
                                targetFragment = UserDictionaryList.class;
                            }
                            startFragment(InputMethodAndLanguageSettings.this,
                                    targetFragment.getCanonicalName(), -1, -1, extras);
                            return true;
                        }
                    });
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        mSettingsObserver.resume();
        mIm.registerInputDeviceListener(this, null);

        final Preference spellChecker = findPreference(KEY_SPELL_CHECKERS);
        if (spellChecker != null) {
            final TextServicesManager tsm = (TextServicesManager) getSystemService(
                    Context.TEXT_SERVICES_MANAGER_SERVICE);
            if (!tsm.isSpellCheckerEnabled()) {
                spellChecker.setSummary(R.string.switch_off_text);
            } else {
                final SpellCheckerInfo sci = tsm.getCurrentSpellChecker();
                if (sci != null) {
                    spellChecker.setSummary(sci.loadLabel(getPackageManager()));
                } else {
                    spellChecker.setSummary(R.string.spell_checker_not_selected);
                }
            }
        }

        if (!mShowsOnlyFullImeAndKeyboardList) {
            if (mLanguagePref != null) {
                String localeNames = getLocaleNames(getActivity());
                mLanguagePref.setSummary(localeNames);
            }

            updateUserDictionaryPreference(findPreference(KEY_USER_DICTIONARY_SETTINGS));
        }

        updateGameControllers();

        // Refresh internal states in mInputMethodSettingValues to keep the latest
        // "InputMethodInfo"s and "InputMethodSubtype"s
        mInputMethodSettingValues.refreshAllInputMethodAndSubtypes();
    }

    @Override
    public void onPause() {
        super.onPause();

        mIm.unregisterInputDeviceListener(this);
        mSettingsObserver.pause();

        // TODO: Consolidate the logic to InputMethodSettingsWrapper
        InputMethodAndSubtypeUtil.saveInputMethodSubtypeList(
                this, getContentResolver(), mInputMethodSettingValues.getInputMethodList(), false);
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        updateGameControllers();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        updateGameControllers();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        updateGameControllers();
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        // Input Method stuff
        if (Utils.isMonkeyRunning()) {
            return false;
        }
        if (preference instanceof PreferenceScreen) {
            if (preference.getFragment() != null) {
                // Fragment will be handled correctly by the super class.
            } else if (KEY_CURRENT_INPUT_METHOD.equals(preference.getKey())) {
                final InputMethodManager imm = (InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showInputMethodPicker(false /* showAuxiliarySubtypes */);
            }
        } else if (preference instanceof SwitchPreference) {
            final SwitchPreference pref = (SwitchPreference) preference;
            if (pref == mGameControllerCategory.findPreference("vibrate_input_devices")) {
                System.putInt(getContentResolver(), Settings.System.VIBRATE_INPUT_DEVICES,
                        pref.isChecked() ? 1 : 0);
                return true;
            }
        }
        return super.onPreferenceTreeClick(preference);
    }

    private static String getLocaleNames(Context context) {
        final LocaleList locales = LocalePicker.getLocales();
        final Locale displayLocale = Locale.getDefault();
        return LocaleHelper.toSentenceCase(
                LocaleHelper.getDisplayLocaleList(
                        locales, displayLocale, 2 /* Show up to two locales from the list */),
                displayLocale);
    }

    @Override
    public void onSaveInputMethodPreference(final InputMethodPreference pref) {
        final InputMethodInfo imi = pref.getInputMethodInfo();
        if (!pref.isChecked()) {
            // An IME is being disabled. Save enabled subtypes of the IME to shared preference to be
            // able to re-enable these subtypes when the IME gets re-enabled.
            saveEnabledSubtypesOf(imi);
        }
        final boolean hasHardwareKeyboard = getResources().getConfiguration().keyboard
                == Configuration.KEYBOARD_QWERTY;
        InputMethodAndSubtypeUtil.saveInputMethodSubtypeList(this, getContentResolver(),
                mImm.getInputMethodList(), hasHardwareKeyboard);
        // Update input method settings and preference list.
        mInputMethodSettingValues.refreshAllInputMethodAndSubtypes();
        if (pref.isChecked()) {
            // An IME is being enabled. Load the previously enabled subtypes from shared preference
            // and enable these subtypes.
            restorePreviouslyEnabledSubtypesOf(imi);
        }
    }

    private void saveEnabledSubtypesOf(final InputMethodInfo imi) {
        final HashSet<String> enabledSubtypeIdSet = new HashSet<>();
        final List<InputMethodSubtype> enabledSubtypes = mImm.getEnabledInputMethodSubtypeList(
                imi, true /* allowsImplicitlySelectedSubtypes */);
        for (final InputMethodSubtype subtype : enabledSubtypes) {
            final String subtypeId = Integer.toString(subtype.hashCode());
            enabledSubtypeIdSet.add(subtypeId);
        }
        final HashMap<String, HashSet<String>> imeToEnabledSubtypeIdsMap =
                loadPreviouslyEnabledSubtypeIdsMap();
        final String imiId = imi.getId();
        imeToEnabledSubtypeIdsMap.put(imiId, enabledSubtypeIdSet);
        savePreviouslyEnabledSubtypeIdsMap(imeToEnabledSubtypeIdsMap);
    }

    private void restorePreviouslyEnabledSubtypesOf(final InputMethodInfo imi) {
        final HashMap<String, HashSet<String>> imeToEnabledSubtypeIdsMap =
                loadPreviouslyEnabledSubtypeIdsMap();
        final String imiId = imi.getId();
        final HashSet<String> enabledSubtypeIdSet = imeToEnabledSubtypeIdsMap.remove(imiId);
        if (enabledSubtypeIdSet == null) {
            return;
        }
        savePreviouslyEnabledSubtypeIdsMap(imeToEnabledSubtypeIdsMap);
        InputMethodAndSubtypeUtil.enableInputMethodSubtypesOf(
                getContentResolver(), imiId, enabledSubtypeIdSet);
    }

    private HashMap<String, HashSet<String>> loadPreviouslyEnabledSubtypeIdsMap() {
        final Context context = getActivity();
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String imesAndSubtypesString = prefs.getString(KEY_PREVIOUSLY_ENABLED_SUBTYPES, null);
        return InputMethodAndSubtypeUtil.parseInputMethodsAndSubtypesString(imesAndSubtypesString);
    }

    private void savePreviouslyEnabledSubtypeIdsMap(
            final HashMap<String, HashSet<String>> subtypesMap) {
        final Context context = getActivity();
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String imesAndSubtypesString = InputMethodAndSubtypeUtil
                .buildInputMethodsAndSubtypesString(subtypesMap);
        prefs.edit().putString(KEY_PREVIOUSLY_ENABLED_SUBTYPES, imesAndSubtypesString).apply();
    }

    private void updateCurrentImeName() {
        final Context context = getActivity();
        if (context == null || mImm == null) return;
        final Preference curPref = getPreferenceScreen().findPreference(KEY_CURRENT_INPUT_METHOD);
        if (curPref != null) {
            final CharSequence curIme =
                    mInputMethodSettingValues.getCurrentInputMethodName(context);
            if (!TextUtils.isEmpty(curIme)) {
                synchronized (this) {
                    curPref.setSummary(curIme);
                }
            }
        }
    }

    private void showKeyboardLayoutDialog(InputDeviceIdentifier inputDeviceIdentifier) {
        KeyboardLayoutDialogFragment fragment = (KeyboardLayoutDialogFragment)
                getFragmentManager().findFragmentByTag("keyboardLayout");
        if (fragment == null) {
            fragment = new KeyboardLayoutDialogFragment(inputDeviceIdentifier);
            fragment.setTargetFragment(this, 0);
            fragment.show(getActivity().getFragmentManager(), "keyboardLayout");
        }
    }

    @Override
    public void onSetupKeyboardLayouts(InputDeviceIdentifier inputDeviceIdentifier) {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClass(getActivity(), KeyboardLayoutPickerActivity.class);
        intent.putExtra(KeyboardLayoutPickerFragment.EXTRA_INPUT_DEVICE_IDENTIFIER,
                inputDeviceIdentifier);
        mIntentWaitingForResult = intent;
        startActivityForResult(intent, 0);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (mIntentWaitingForResult != null) {
            InputDeviceIdentifier inputDeviceIdentifier = mIntentWaitingForResult
                    .getParcelableExtra(KeyboardLayoutPickerFragment.EXTRA_INPUT_DEVICE_IDENTIFIER);
            mIntentWaitingForResult = null;
            showKeyboardLayoutDialog(inputDeviceIdentifier);
        }
    }

    private void updateGameControllers() {
        if (haveInputDeviceWithVibrator()) {
            getPreferenceScreen().addPreference(mGameControllerCategory);

            SwitchPreference pref = (SwitchPreference)
                    mGameControllerCategory.findPreference("vibrate_input_devices");
            pref.setChecked(System.getInt(getContentResolver(),
                    Settings.System.VIBRATE_INPUT_DEVICES, 1) > 0);
        } else {
            getPreferenceScreen().removePreference(mGameControllerCategory);
        }
    }

    private static boolean haveInputDeviceWithVibrator() {
        final int[] devices = InputDevice.getDeviceIds();
        for (int i = 0; i < devices.length; i++) {
            InputDevice device = InputDevice.getDevice(devices[i]);
            if (device != null && !device.isVirtual() && device.getVibrator().hasVibrator()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (mPhysicalKeyboard != null) {
            boolean visible = newConfig.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
            mPhysicalKeyboard.setVisible(visible);
        }
    }

    private class SettingsObserver extends ContentObserver {
        private Context mContext;

        public SettingsObserver(Handler handler, Context context) {
            super(handler);
            mContext = context;
        }

        @Override public void onChange(boolean selfChange) {
            updateCurrentImeName();
        }

        public void resume() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, this);
            cr.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE), false, this);
        }

        public void pause() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }
    }

    private static class SummaryProvider implements SummaryLoader.SummaryProvider {

        private final Context mContext;
        private final SummaryLoader mSummaryLoader;

        public SummaryProvider(Context context, SummaryLoader summaryLoader) {
            mContext = context;
            mSummaryLoader = summaryLoader;
        }

        @Override
        public void setListening(boolean listening) {
            if (listening) {
                String localeNames = getLocaleNames(mContext);
                mSummaryLoader.setSummary(this, localeNames);
            }
        }
    }

    public static final SummaryLoader.SummaryProviderFactory SUMMARY_PROVIDER_FACTORY
            = new SummaryLoader.SummaryProviderFactory() {
        @Override
        public SummaryLoader.SummaryProvider createSummaryProvider(Activity activity,
                                                                   SummaryLoader summaryLoader) {
            return new SummaryProvider(activity, summaryLoader);
        }
    };

    public static final Indexable.SearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider() {
        @Override
        public List<SearchIndexableRaw> getRawDataToIndex(Context context, boolean enabled) {
            List<SearchIndexableRaw> indexables = new ArrayList<>();

            final String screenTitle = context.getString(R.string.language_keyboard_settings_title);

            // Locale picker.
            if (context.getAssets().getLocales().length > 1) {
                String localeNames = getLocaleNames(context);
                SearchIndexableRaw indexable = new SearchIndexableRaw(context);
                indexable.key = KEY_PHONE_LANGUAGE;
                indexable.title = context.getString(R.string.phone_language);
                indexable.summaryOn = localeNames;
                indexable.summaryOff = localeNames;
                indexable.screenTitle = screenTitle;
                indexables.add(indexable);
            }

            // Spell checker.
            SearchIndexableRaw indexable = new SearchIndexableRaw(context);
            indexable.key = KEY_SPELL_CHECKERS;
            indexable.title = context.getString(R.string.spellcheckers_settings_title);
            indexable.screenTitle = screenTitle;
            indexable.keywords = context.getString(R.string.keywords_spell_checker);
            indexables.add(indexable);

            // User dictionary.
            if (UserDictionaryList.getUserDictionaryLocalesSet(context) != null) {
                indexable = new SearchIndexableRaw(context);
                indexable.key = "user_dict_settings";
                indexable.title = context.getString(R.string.user_dict_settings_title);
                indexable.screenTitle = screenTitle;
                indexables.add(indexable);
            }

            InputMethodSettingValuesWrapper immValues = InputMethodSettingValuesWrapper
                    .getInstance(context);
            immValues.refreshAllInputMethodAndSubtypes();

            // Current IME.
            String currImeName = immValues.getCurrentInputMethodName(context).toString();
            indexable = new SearchIndexableRaw(context);
            indexable.key = KEY_CURRENT_INPUT_METHOD;
            indexable.title = context.getString(R.string.current_input_method);
            indexable.summaryOn = currImeName;
            indexable.summaryOff = currImeName;
            indexable.screenTitle = screenTitle;
            indexables.add(indexable);

            InputMethodManager inputMethodManager = (InputMethodManager) context.getSystemService(
                    Context.INPUT_METHOD_SERVICE);

            // All other IMEs.
            List<InputMethodInfo> inputMethods = immValues.getInputMethodList();
            final int inputMethodCount = (inputMethods == null ? 0 : inputMethods.size());
            for (int i = 0; i < inputMethodCount; ++i) {
                InputMethodInfo inputMethod = inputMethods.get(i);
                List<InputMethodSubtype> subtypes = inputMethodManager
                        .getEnabledInputMethodSubtypeList(inputMethod, true);
                String summary = InputMethodAndSubtypeUtil.getSubtypeLocaleNameListAsSentence(
                        subtypes, context, inputMethod);

                ServiceInfo serviceInfo = inputMethod.getServiceInfo();
                ComponentName componentName = new ComponentName(serviceInfo.packageName,
                        serviceInfo.name);

                indexable = new SearchIndexableRaw(context);
                indexable.key = componentName.flattenToString();
                indexable.title = inputMethod.loadLabel(context.getPackageManager()).toString();
                indexable.summaryOn = summary;
                indexable.summaryOff = summary;
                indexable.screenTitle = screenTitle;
                indexables.add(indexable);
            }

            // Hard keyboards
            InputManager inputManager = (InputManager) context.getSystemService(
                    Context.INPUT_SERVICE);
            boolean hasHardKeyboards = false;

            final int[] devices = InputDevice.getDeviceIds();
            for (int i = 0; i < devices.length; i++) {
                InputDevice device = InputDevice.getDevice(devices[i]);
                if (device == null || device.isVirtual() || !device.isFullKeyboard()) {
                    continue;
                }

                hasHardKeyboards = true;

                InputDeviceIdentifier identifier = device.getIdentifier();
                String keyboardLayoutDescriptor =
                        inputManager.getCurrentKeyboardLayoutForInputDevice(identifier);
                KeyboardLayout keyboardLayout = keyboardLayoutDescriptor != null ?
                        inputManager.getKeyboardLayout(keyboardLayoutDescriptor) : null;

                String summary;
                if (keyboardLayout != null) {
                    summary = keyboardLayout.toString();
                } else {
                    summary = context.getString(R.string.keyboard_layout_default_label);
                }

                indexable = new SearchIndexableRaw(context);
                indexable.key = device.getName();
                indexable.title = device.getName();
                indexable.summaryOn = summary;
                indexable.summaryOff = summary;
                indexable.screenTitle = screenTitle;
                indexables.add(indexable);
            }

            if (hasHardKeyboards) {
                // Hard keyboard category.
                indexable = new SearchIndexableRaw(context);
                indexable.key = "builtin_keyboard_settings";
                indexable.title = context.getString(
                        R.string.builtin_keyboard_settings_title);
                indexable.screenTitle = screenTitle;
                indexables.add(indexable);
            }

            // Text-to-speech.
            TtsEngines ttsEngines = new TtsEngines(context);
            if (!ttsEngines.getEngines().isEmpty()) {
                indexable = new SearchIndexableRaw(context);
                indexable.key = "tts_settings";
                indexable.title = context.getString(R.string.tts_settings_title);
                indexable.screenTitle = screenTitle;
                indexable.keywords = context.getString(R.string.keywords_text_to_speech_output);
                indexables.add(indexable);
            }

            // Pointer settings.
            indexable = new SearchIndexableRaw(context);
            indexable.key = "pointer_settings_category";
            indexable.title = context.getString(R.string.pointer_settings_category);
            indexable.screenTitle = screenTitle;
            indexables.add(indexable);

            indexable = new SearchIndexableRaw(context);
            indexable.key = "pointer_speed";
            indexable.title = context.getString(R.string.pointer_speed);
            indexable.screenTitle = screenTitle;
            indexables.add(indexable);

            if (MKHardwareManager.getInstance(context).
                    isSupported(MKHardwareManager.FEATURE_TOUCH_HOVERING)) {
                indexable = new SearchIndexableRaw(context);
                indexable.key = "touch_hovering";
                indexable.title = context.getString(R.string.touchscreen_hovering_title);
                indexable.summaryOn = context.getString(R.string.touchscreen_hovering_summary);
                indexable.summaryOff = context.getString(R.string.touchscreen_hovering_summary);
                indexable.screenTitle = screenTitle;
                indexables.add(indexable);
            }

            // Game controllers.
            if (haveInputDeviceWithVibrator()) {
                indexable = new SearchIndexableRaw(context);
                indexable.key = "vibrate_input_devices";
                indexable.title = context.getString(R.string.vibrate_input_devices);
                indexable.summaryOn = context.getString(R.string.vibrate_input_devices_summary);
                indexable.summaryOff = context.getString(R.string.vibrate_input_devices_summary);
                indexable.screenTitle = screenTitle;
                indexables.add(indexable);
            }

            return indexables;
        }
    };
}
