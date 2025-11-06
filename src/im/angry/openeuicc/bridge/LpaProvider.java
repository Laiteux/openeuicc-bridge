package im.angry.openeuicc.bridge;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import java.time.Instant;
import java.nio.charset.*;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.HttpURLConnection;

import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.sync.Mutex;
import kotlinx.coroutines.sync.MutexKt;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import im.angry.openeuicc.OpenEuiccApplication;
import im.angry.openeuicc.di.AppContainer;
import im.angry.openeuicc.di.UnprivilegedAppContainer;
import im.angry.openeuicc.core.EuiccChannel;
import im.angry.openeuicc.core.EuiccChannelManager;
import im.angry.openeuicc.core.DefaultEuiccChannelManager;
import im.angry.openeuicc.util.UiccCardInfoCompat;
import im.angry.openeuicc.util.UiccPortInfoCompat;
import im.angry.openeuicc.util.LPAUtilsKt;
import im.angry.openeuicc.util.ActivationCode;
import im.angry.openeuicc.util.PreferenceUtilsKt;
import im.angry.openeuicc.util.PreferenceFlowWrapper;
import net.typeblog.lpac_jni.LocalProfileInfo;
import net.typeblog.lpac_jni.ProfileDownloadCallback;

public class LpaProvider extends ContentProvider
{
    private AppContainer appContainer;
    private Mutex mutex;
    private Gson gson;

    @Override
    public boolean onCreate()
    {
        appContainer = ((OpenEuiccApplication) getContext().getApplicationContext()).getAppContainer();

        mutex = MutexKt.Mutex(false);

        gson = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder)
    {
        MatrixCursor rows;

        final String endpoint = uri.getLastPathSegment();
        final Map<String, String> args = getArgsFromUri(uri);

        boolean[] json = new boolean[1];

        if (endpoint == null)
        {
            rows = error("no_endpoint");
        }
        else
        {
            try
            {
                rows = (MatrixCursor) BuildersKt.runBlocking
                (
                    EmptyCoroutineContext.INSTANCE,
                    (_, continuation) -> MutexKt.withLock
                    (
                        mutex,
                        null,
                        new Function0<MatrixCursor>()
                        {
                            @Override
                            public MatrixCursor invoke()
                            {
                                MatrixCursor rows;

                                try
                                {
                                    rows = switch (endpoint)
                                    {
                                        // out: string ping=pong
                                        case "ping" -> handlePing(args);
                                        // out (many): string name, bool enabled
                                        case "preferences" -> handleGetPreferences(args);
                                        // in: string name, bool enabled
                                        // out: bool success
                                        case "setPreference" -> handleSetPreference(args);
                                        // out (many, can be empty): int slotId, int portId
                                        case "cards" -> handleGetCards(args);
                                        // in: int slotId, int portId
                                        // out (many, can be empty): string iccid, bool enabled, string name, string nickname
                                        case "profiles" -> handleGetProfiles(args);
                                        // in: int slotId, int portId
                                        // out (single, can be empty): string iccid, bool enabled, string name, string nickname
                                        case "activeProfile" -> handleGetActiveProfile(args);
                                        // in: int slotId, int portId, (either {string activationCode} or {string address, string? matchingId}), string? confirmationCode, string? imei, string? callbackUrl
                                        // out (single, can be empty): string iccid, bool enabled, string name, string nickname
                                        case "downloadProfile" -> handleDownloadProfile(args);
                                        // in: int slotId, int portId, string iccid
                                        // out: bool success
                                        case "deleteProfile" -> handleDeleteProfile(args);
                                        // in: int slotId, int portId, string iccid, bool refresh=true
                                        // out: bool success
                                        case "enableProfile" -> handleEnableProfile(args);
                                        // in: int slotId, int portId, string iccid, bool refresh=true
                                        // out: bool success
                                        case "disableProfile" -> handleDisableProfile(args);
                                        // in: int slotId, int portId, bool refresh=true
                                        // out: bool success
                                        case "disableActiveProfile" -> handleDisableActiveProfile(args);
                                        // in: int slotId, int portId, string iccid, bool enable=true, bool refresh=true
                                        // out: bool success
                                        case "switchProfile" -> handleSwitchProfile(args);
                                        // in: int slotId, int portId, string iccid, string nickname
                                        // out: bool success
                                        case "setProfileNickname" -> handleSetProfileNickname(args);
                                        default -> error("unknown_endpoint");
                                    };
                                }
                                catch (Exception ex)
                                {
                                    rows = error(ex.getMessage());
                                }

                                return rows;
                            }
                        },
                        continuation
                    )
                );
            }
            catch (Exception ex)
            {
                rows = error(ex.getMessage());
            }
        }

        rows = projectColumns(rows, projection, new String[] { "error" });

        if (tryGetArgAsBoolean(args, "json", json) && json[0])
            rows = row("rows", rowsToJson(rows));

        return rows;
    }

    // region Mandatory Overrides

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public String getType(Uri uri) { return null; }

    // endregion

    // region Handlers

    private MatrixCursor handlePing(Map<String, String> args)
    {
        return row("ping", "pong");
    }

    private MatrixCursor handleGetPreferences(Map<String, String> args) throws Exception
    {
        var preferences = List.of
        (
            "verboseLogging",
            "safeguardActiveProfile",
            "filterProfileList",
            "ignoreTlsCertificate",
            "notificationsDownload",
            "notificationsDelete",
            "notificationsSwitch"
        );

        if (!(appContainer instanceof UnprivilegedAppContainer))
            preferences.add(1, "forceUseTelephonyManager");

        var columns = new String[] { "name", "enabled" };
        var values = new Object[preferences.size()][2];

        for (int valIndex = 0; valIndex < preferences.size(); valIndex++)
        {
            String name = preferences.get(valIndex);

            values[valIndex][0] = name;
            values[valIndex][1] = getPreference(name);
        }

        return rows(columns, values);
    }

    private MatrixCursor handleSetPreference(Map<String, String> args) throws Exception
    {
        String[] name = new String[1];
        boolean[] enabled = new boolean[1];

        if (!tryGetArgAsString(args, "name", name))
            return missingArgError("name");

        if (!tryGetArgAsBoolean(args, "enabled", enabled))
            return missingArgError("enabled");

        setPreference(name[0], enabled[0]);

        return success();
    }

    private MatrixCursor handleGetCards(Map<String, String> args) throws Exception
    {
        var euiccChannelManager = (DefaultEuiccChannelManager) appContainer.getEuiccChannelManager();

        var getUiccCardsMethod = DefaultEuiccChannelManager.class.getDeclaredMethod("getUiccCards");
        getUiccCardsMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        var cards = (List<UiccCardInfoCompat>) getUiccCardsMethod.invoke(euiccChannelManager);

        var rows = new MatrixCursor(new String[]
        {
            "slotId",
            "portId"
        });

        for (UiccCardInfoCompat card : cards)
        {
            for (UiccPortInfoCompat port : card.getPorts())
            {
                int slotId = card.getPhysicalSlotIndex();
                int portId = port.getPortIndex();

                var euiccChannel = findEuiccChannel(euiccChannelManager, slotId, portId);

                if (euiccChannel != null)
                {
                    rows.addRow(new Object[]
                    {
                        slotId,
                        portId
                    });
                }
            }
        }

        return rows;
    }

    private MatrixCursor handleGetProfiles(Map<String, String> args) throws Exception
    {
        var profiles = getProfiles(args);

        return profiles(profiles);
    }

    private MatrixCursor handleGetActiveProfile(Map<String, String> args) throws Exception
    {
        var profiles = getProfiles(args);

        var enabledProfile = LPAUtilsKt.getEnabled(profiles);

        if (enabledProfile == null)
            return empty();

        return profile(enabledProfile);
    }

    private MatrixCursor handleDownloadProfile(Map<String, String> args) throws Exception
    {
        String[] address = new String[1];
        String[] matchingId = { args.get("matchingId") };
        String[] confirmationCode = { args.get("confirmationCode") };
        String imei = args.get("imei");

        String[] activationCodeArg = new String[1];

        if (tryGetArgAsString(args, "activationCode", activationCodeArg))
        {
            var activationCode = ActivationCode.Companion.fromString(activationCodeArg[0]);

            address[0] = activationCode.getAddress();
            matchingId[0] = activationCode.getMatchingId();

            if (activationCode.getConfirmationCodeRequired())
                if (!tryGetArgAsString(args, "confirmationCode", confirmationCode))
                    return missingArgError("confirmationCode");
        }
        else if (!tryGetArgAsString(args, "address", address))
            return missingArgError("activationCode_or_address");

        var profilesBefore = getProfiles(args);

        var iccidsBefore = profilesBefore.stream()
            .map(LocalProfileInfo::getIccid)
            .collect(Collectors.toSet());

        withEuiccChannel
        (
            args,
            (channel, _) ->
            {
                channel.getLpa().downloadProfile
                (
                    address[0],
                    matchingId[0],
                    imei,
                    confirmationCode[0],
                    new ProfileDownloadCallback()
                    {
                        @Override
                        public void onStateUpdate(ProfileDownloadCallback.DownloadState state)
                        {
                            new Thread(() ->
                            {
                                try
                                {
                                    String[] callbackUrl = new String[1];

                                    if (tryGetArgAsString(args, "callbackUrl", callbackUrl))
                                    {
                                        var url = new URI(callbackUrl[0]).toURL();

                                        var data = new LinkedHashMap<String, Object>()
                                        {{
                                            put("timestamp", Instant.now().getEpochSecond());
                                            put("state", state.name());
                                            put("progress", state.getProgress());
                                            put("address", address[0]);
                                            put("matchingId", matchingId[0]);
                                            put("confirmationCode", confirmationCode[0]);
                                            put("imei", imei);
                                        }};

                                        httpPostAsJson(url, data);
                                    }
                                }
                                catch (Exception ex)
                                {
                                    // ignored
                                }
                            }).start();
                        }
                    }
                );

                return null;
            }
        );

        var profilesAfter = getProfiles(args);

        var downloadedProfile = profilesAfter.stream()
            .filter(p -> !iccidsBefore.contains(p.getIccid()))
            .findFirst()
            .orElse(null);

        if (downloadedProfile == null)
            return empty();

        // boolean[] enable = new boolean[1];
        // boolean[] refresh = new boolean[1];

        // if (tryGetArgAsBoolean(args, "enable", enable) && enable[0])
        // {
        //     if (!tryGetArgAsBoolean(args, "refresh", refresh))
        //         refresh[0] = true;

        //     String iccid = downloadedProfile.getIccid();

        //     try
        //     {
        //         withEuiccChannel
        //         (
        //             args,
        //             (channel, _) -> channel.getLpa().enableProfile(iccid, refresh[0])
        //         );

        //         profilesAfter = getProfiles(args);

        //         downloadedProfile = profilesAfter.stream()
        //             .filter(p -> p.getIccid().equals(iccid))
        //             .findFirst()
        //             .orElseThrow(); // should never happen
        //     }
        //     catch (Exception ex)
        //     {
        //         // ignored
        //     }
        // }

        return profile(downloadedProfile);
    }

    private MatrixCursor handleDeleteProfile(Map<String, String> args) throws Exception
    {
        String[] iccid = new String[1];

        if (!tryGetArgAsString(args, "iccid", iccid))
            return missingArgError("iccid");

        safeguardActiveProfile(args, iccid[0]);

        boolean success = withEuiccChannel
        (
            args,
            (channel, _) -> channel.getLpa().deleteProfile(iccid[0])
        );

        return success(success);
    }

    private MatrixCursor handleEnableProfile(Map<String, String> args) throws Exception
    {
        String[] iccid = new String[1];
        boolean[] refresh = new boolean[1];

        if (!tryGetArgAsString(args, "iccid", iccid))
            return missingArgError("iccid");

        if (!tryGetArgAsBoolean(args, "refresh", refresh))
            refresh[0] = true;

        boolean success = withEuiccChannel
        (
            args,
            (channel, _) -> channel.getLpa().enableProfile(iccid[0], refresh[0])
        );

        return success(success);
    }

    private MatrixCursor handleDisableProfile(Map<String, String> args) throws Exception
    {
        String[] iccid = new String[1];
        boolean[] refresh = new boolean[1];

        if (!tryGetArgAsString(args, "iccid", iccid))
            return missingArgError("iccid");

        if (!tryGetArgAsBoolean(args, "refresh", refresh))
            refresh[0] = true;

        safeguardActiveProfile(args, iccid[0]);

        boolean success = withEuiccChannel
        (
            args,
            (channel, _) -> channel.getLpa().disableProfile(iccid[0], refresh[0])
        );

        return success(success);
    }

    private MatrixCursor handleDisableActiveProfile(Map<String, String> args) throws Exception
    {
        boolean[] refresh = new boolean[1];

        if (!tryGetArgAsBoolean(args, "refresh", refresh))
            refresh[0] = true;

        safeguardActiveProfile(args, null);

        String iccid = withEuiccChannel
        (
            args,
            (channel, _) -> LPAUtilsKt.disableActiveProfileKeepIccId(channel.getLpa(), refresh[0])
        );

        return success();

        // if (iccid == null)
        //     return empty();

        // var profiles = getProfiles(args);

        // var profile = profiles.stream()
        //     .filter(p -> iccid.equals(p.getIccid()))
        //     .findFirst()
        //     .get();

        // if (profile == null)
        //     return empty();

        // return profile(profile);
    }

    private MatrixCursor handleSwitchProfile(Map<String, String> args) throws Exception
    {
        String[] iccid = new String[1];
        boolean[] enable = new boolean[1];
        boolean[] refresh = new boolean[1];

        if (!tryGetArgAsString(args, "iccid", iccid))
            return missingArgError("iccid");

        if (!tryGetArgAsBoolean(args, "enable", enable))
            enable[0] = true;

        if (!tryGetArgAsBoolean(args, "refresh", refresh))
            refresh[0] = true;

        boolean success = withEuiccChannel
        (
            args,
            (channel, _) -> LPAUtilsKt.switchProfile(channel.getLpa(), iccid[0], enable[0], refresh[0])
        );

        return success(success);
    }

    private MatrixCursor handleSetProfileNickname(Map<String, String> args) throws Exception
    {
        String[] iccid = new String[1];
        String[] nickname = new String[1];

        if (!tryGetArgAsString(args, "iccid", iccid))
            return missingArgError("iccid");

        if (!tryGetArgAsString(args, "nickname", nickname))
            return missingArgError("nickname");

        withEuiccChannel
        (
            args,
            (channel, _) ->
            {
                channel.getLpa().setNickname(iccid[0], nickname[0]);
                return null;
            }
        );

        return success();
    }

    // endregion

    // region LPA Helpers

    private static EuiccChannel findEuiccChannel(DefaultEuiccChannelManager euiccChannelManager, int slotId, int portId) throws Exception
    {
        var findEuiccChannelByPortMethod = DefaultEuiccChannelManager.class.getDeclaredMethod("findEuiccChannelByPort", int.class, int.class, Continuation.class);
        findEuiccChannelByPortMethod.setAccessible(true);

        return (EuiccChannel) BuildersKt.runBlocking
        (
            EmptyCoroutineContext.INSTANCE,
            (_, continuation) ->
            {
                try
                {
                    return findEuiccChannelByPortMethod.invoke(euiccChannelManager, slotId, portId, continuation);
                }
                catch (Exception ex)
                {
                    return null;
                }
            }
        );
    }

    @SuppressWarnings("unchecked")
    private <T> T withEuiccChannel(Map<String, String> args, Function2<EuiccChannel, Continuation<? super T>, ?> operation) throws Exception
    {
        var slotId = new int[1];
        var portId = new int[1];
        requireSlotAndPort(args, slotId, portId);

        var euiccChannelManager = appContainer.getEuiccChannelManager();

        return (T) BuildersKt.runBlocking
        (
            EmptyCoroutineContext.INSTANCE,
            (_, continuation) -> euiccChannelManager.withEuiccChannel(slotId[0], portId[0], operation, continuation)
        );
    }

    private List<LocalProfileInfo> getProfiles(Map<String, String> args) throws Exception
    {
        @SuppressWarnings("unchecked")
        var profiles = (List<LocalProfileInfo>) withEuiccChannel
        (
            args,
            (channel, _) -> channel.getLpa().getProfiles()
        );

        boolean filterProfileList = getPreference("filterProfileList");

        if (filterProfileList)
            return LPAUtilsKt.getOperational(profiles);

        return profiles;
    }

    // endregion

    // region Preference Helpers

    private List<String> invertedPreferences = List.of
    (
        "safeguardActiveProfile",
        "filterProfileList"
    );

    private PreferenceFlowWrapper<Boolean> getPreferenceFlow(String name) throws Exception
    {
        var preferenceRepository = PreferenceUtilsKt.getPreferenceRepository(getContext());

        return switch (name)
        {
            case "verboseLogging" -> preferenceRepository.getVerboseLoggingFlow();
            case "forceUseTelephonyManager" -> preferenceRepository.getForceUseTMAPIFlow();
            case "safeguardActiveProfile" -> preferenceRepository.getDisableSafeguardFlow();
            case "filterProfileList" -> preferenceRepository.getUnfilteredProfileListFlow();
            case "ignoreTlsCertificate" -> preferenceRepository.getIgnoreTLSCertificateFlow();
            case "notificationsDownload" -> preferenceRepository.getNotificationDownloadFlow();
            case "notificationsDelete" -> preferenceRepository.getNotificationDeleteFlow();
            case "notificationsSwitch" -> preferenceRepository.getNotificationSwitchFlow();
            default -> throw new Exception("unknown_preference_name");
        };
    }

    private boolean getPreference(String name) throws Exception
    {
        var preferenceFlow = getPreferenceFlow(name);

        boolean enabled = BuildersKt.runBlocking
        (
            EmptyCoroutineContext.INSTANCE,
            (_, continuation) -> FlowKt.first(preferenceFlow, continuation)
        );

        if (invertedPreferences.contains(name))
            enabled = !enabled;

        return enabled;
    }

    private void setPreference(String name, boolean enabled) throws Exception
    {
        var preferenceFlow = getPreferenceFlow(name);

        if (invertedPreferences.contains(name))
            enabled = !enabled;

        final boolean enabledFinal = enabled;

        BuildersKt.runBlocking
        (
            EmptyCoroutineContext.INSTANCE,
            (_, continuation) -> preferenceFlow.updatePreference(enabledFinal, continuation)
        );
    }

    private void safeguardActiveProfile(Map<String, String> args, String iccid) throws Exception
    {
        int[] slotId = new int[1];
        int[] portId = new int[1];
        requireSlotAndPort(args, slotId, portId);

        if (slotId[0] == EuiccChannelManager.USB_CHANNEL_ID)
            return;

        boolean safeguardEnabled = getPreference("safeguardActiveProfile");

        if (!safeguardEnabled)
            return;

        boolean isTargetActive = iccid == null;

        if (!isTargetActive)
        {
            var profiles = getProfiles(args);
            var activeProfile = LPAUtilsKt.getEnabled(profiles);

            if (activeProfile == null)
                return;

            isTargetActive = iccid.equals(activeProfile.getIccid());
        }

        if (isTargetActive)
            throw new Exception("safeguard_active_profile");
    }

    // endregion

    // region Arg Helpers

    private static Map<String, String> getArgsFromUri(Uri uri)
    {
        var args = new LinkedHashMap<String, String>();

        for (String name : uri.getQueryParameterNames())
        {
            args.put(name, URLDecoder.decode(uri.getQueryParameter(name), StandardCharsets.UTF_8));
        }

        return args;
    }

    private static boolean tryGetArgAsString(Map<String, String> args, String key, String[] out)
    {
        String arg = args.get(key);

        if (arg == null || arg.isEmpty())
            return false;

        out[0] = arg;
        return true;
    }

    private static boolean tryGetArgAsInt(Map<String, String> args, String key, int[] out)
    {
        String arg = args.get(key);

        if (arg == null || arg.isEmpty())
            return false;

        try
        {
            out[0] = Integer.parseInt(arg);
            return true;
        }
        catch (NumberFormatException ex)
        {
            return false;
        }
    }

    private static boolean tryGetArgAsBoolean(Map<String, String> args, String key, boolean[] out)
    {
        String arg = args.get(key);

        if (arg == null)
            return false;

        out[0] = arg.isEmpty()
            || arg.equals("1")
            || arg.toLowerCase().startsWith("y")
            || arg.equalsIgnoreCase("on")
            || arg.equalsIgnoreCase("true");

        return true;
    }

    private static void requireSlotAndPort(Map<String, String> args, int[] slotIdOut, int[] portIdOut) throws Exception
    {
        final String slotIdArg = "slotId";
        final String portIdArg = "portId";

        if (!tryGetArgAsInt(args, slotIdArg, slotIdOut))
            throw new Exception("missing_arg_" + slotIdArg);

        if (!tryGetArgAsInt(args, portIdArg, portIdOut))
            throw new Exception("missing_arg_" + portIdArg);
    }

    // endregion

    // region Row Helpers

    private static MatrixCursor rows(String[] columns, Object[][] values)
    {
        var rows = new MatrixCursor(columns);

        for (Object[] rowValues : values)
        {
            rows.addRow(rowValues);
        }

        return rows;
    }

    private static MatrixCursor row(String column, String value)
    {
        return rows(new String[] { column }, new Object[][] { new Object[] { value } });
    }

    private static MatrixCursor empty()
    {
        return new MatrixCursor(new String[0]);
    }

    private static MatrixCursor success()
    {
        return success(true);
    }

    private static MatrixCursor success(boolean success)
    {
        return row("success", Boolean.toString(success));
    }

    private static MatrixCursor error(String message)
    {
        return row("error", message);
    }

    private static MatrixCursor missingArgError(String argName)
    {
        return error("missing_arg_" + argName);
    }

    private static MatrixCursor profile(LocalProfileInfo profile)
    {
        return profiles(Collections.singletonList(profile));
    }

    private static MatrixCursor profiles(List<LocalProfileInfo> profiles)
    {
        String[] columns =
        {
            "iccid",
            "enabled",
            "nickname"
        };

        Object[][] rows = profiles.stream()
            .map(p -> new Object[]
            {
                p.getIccid(),
                LPAUtilsKt.isEnabled(p),
                p.getNickName()
            })
            .toArray(Object[][]::new);

        return rows(columns, rows);
    }

    private static MatrixCursor projectColumns(MatrixCursor rows, String[] projection)
    {
        return projectColumns(rows, projection, null);
    }

    private static MatrixCursor projectColumns(MatrixCursor rows, String[] projection, String[] preserve)
    {
        var rowCols = new LinkedHashSet<String>(Arrays.asList(rows.getColumnNames()));
        var outCols = new LinkedHashSet<String>();

        if (projection != null && projection.length > 0)
            outCols.addAll(Arrays.asList(projection));
        else
            outCols.addAll(rowCols);

        if (preserve != null && preserve.length > 0)
        {
            Stream.of(preserve)
                .filter(rowCols::contains)
                .forEach(outCols::add);
        }

        if (outCols.isEmpty())
            return rows;

        var outColsArray = outCols.toArray(new String[0]);
        var outRows = new MatrixCursor(outColsArray);

        rows.moveToPosition(-1);

        while (rows.moveToNext())
        {
            var row = new Object[outColsArray.length];

            for (int rowIndex = 0; rowIndex < outColsArray.length; rowIndex++)
            {
                String colName = outColsArray[rowIndex];
                int colIndex = rows.getColumnIndex(colName);

                if (colIndex < 0)
                {
                    row[rowIndex] = null;
                    continue;
                }

                switch (rows.getType(colIndex))
                {
                    case Cursor.FIELD_TYPE_NULL:
                        row[rowIndex] = null;
                        break;
                    case Cursor.FIELD_TYPE_INTEGER:
                        row[rowIndex] = rows.getLong(colIndex);
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        row[rowIndex] = rows.getDouble(colIndex);
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        row[rowIndex] = rows.getBlob(colIndex);
                        break;
                    case Cursor.FIELD_TYPE_STRING:
                    default:
                        row[rowIndex] = rows.getString(colIndex);
                        break;
                }
            }

            outRows.addRow(row);
        }

        return outRows;
    }

    private String rowsToJson(MatrixCursor rows)
    {
        String[] rowCols = rows.getColumnNames();
        var outRows = new ArrayList<Map<String, Object>>();

        rows.moveToPosition(-1);

        while (rows.moveToNext())
        {
            var row = new LinkedHashMap<String, Object>();

            for (String colName : rowCols)
            {
                int colIndex = rows.getColumnIndex(colName);

                switch (rows.getType(colIndex))
                {
                    case Cursor.FIELD_TYPE_NULL:
                        row.put(colName, null);
                        break;
                    case Cursor.FIELD_TYPE_INTEGER:
                        row.put(colName, rows.getLong(colIndex));
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        row.put(colName, rows.getDouble(colIndex));
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        byte[] blob = rows.getBlob(colIndex);
                        String blobBase64 = Base64.getEncoder().encodeToString(blob);
                        row.put(colName, blobBase64);
                        break;
                    case Cursor.FIELD_TYPE_STRING:
                    default:
                        row.put(colName, rows.getString(colIndex));
                        break;
                }
            }

            var booleanCols = List.of
            (
                "success",
                "enabled"
            );

            for (String colName : booleanCols)
            {
                Object colValue = row.get(colName);

                if (colValue instanceof String)
                {
                    String colValueString = (String) colValue;

                    if (colValueString.equalsIgnoreCase(Boolean.toString(false)) || colValueString.equalsIgnoreCase(Boolean.toString(true)))
                    {
                        row.put(colName, Boolean.parseBoolean(colValueString));
                    }
                }
            }

            outRows.add(row);
        }

        return gson.toJson(outRows);
    }

    // endregion

    // region HTTP Helpers

    private void httpPostAsJson(URL url, Map<String, Object> data) throws Exception
    {
        String json = gson.toJson(data);

        var httpConnection = (HttpURLConnection) url.openConnection();

        httpConnection.setRequestMethod("POST");
        httpConnection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        httpConnection.setDoOutput(true);

        try (var outputStream = httpConnection.getOutputStream())
        {
            outputStream.write(json.getBytes(StandardCharsets.UTF_8));
        }

        httpConnection.getInputStream().close();
    }

    // endregion
}
