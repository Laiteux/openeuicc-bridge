package im.angry.openeuicc.bridge;

import java.util.Collection;
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
import kotlinx.coroutines.sync.Mutex;
import kotlinx.coroutines.sync.MutexKt;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import com.google.gson.GsonBuilder;

import im.angry.openeuicc.OpenEuiccApplication;
import im.angry.openeuicc.di.AppContainer;
import im.angry.openeuicc.core.EuiccChannel;
import im.angry.openeuicc.core.DefaultEuiccChannelManager;
import im.angry.openeuicc.util.UiccCardInfoCompat;
import im.angry.openeuicc.util.UiccPortInfoCompat;
import im.angry.openeuicc.util.LPAUtilsKt;
import im.angry.openeuicc.util.ActivationCode;
import net.typeblog.lpac_jni.LocalProfileInfo;
import net.typeblog.lpac_jni.ProfileDownloadCallback;

public class LpaBridgeProvider extends ContentProvider
{
    private AppContainer appContainer;
    private final Mutex mutex = MutexKt.Mutex(false);

    @Override
    public boolean onCreate()
    {
        appContainer = ((OpenEuiccApplication) getContext().getApplicationContext()).getAppContainer();
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder)
    {
        MatrixCursor rows;

        final String path = uri.getLastPathSegment();
        final Map<String, String> args = getArgsFromUri(uri);

        if (path == null)
        {
            rows = error("no_path");
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
                                    switch (path)
                                    {
                                        case "ping":
                                            // out: string ping=pong
                                            rows = handlePing(args);
                                            break;
                                        case "cards":
                                            // out (many, can be empty): int slotId, int portId
                                            rows = handleGetCards(args);
                                            break;
                                        case "profiles":
                                            // in: int slotId, int portId
                                            // out (many, can be empty): string iccid, bool isEnabled, string name, string nickname
                                            rows = handleGetProfiles(args);
                                            break;
                                        case "activeProfile":
                                            // in: int slotId, int portId
                                            // out (single, can be empty): string iccid, bool isEnabled, string name, string nickname
                                            rows = handleGetActiveProfile(args);
                                            break;
                                        case "downloadProfile":
                                            // in: int slotId, int portId, (either {string activationCode} or {string address, string? matchingId}), string? confirmationCode, string? imei
                                            // out (single, can be empty): string iccid, bool isEnabled, string name, string nickname
                                            rows = handleDownloadProfile(args);
                                            break;
                                        case "deleteProfile":
                                            // in: int slotId, int portId, string iccid
                                            // out: bool success
                                            rows = handleDeleteProfile(args);
                                            break;
                                        case "enableProfile":
                                            // in: int slotId, int portId, string iccid, bool refresh=true
                                            // out: bool success
                                            rows = handleEnableProfile(args);
                                            break;
                                        case "disableProfile":
                                            // in: int slotId, int portId, string iccid, bool refresh=true
                                            // out: bool success
                                            rows = handleDisableProfile(args);
                                            break;
                                        case "disableActiveProfile":
                                            // in: int slotId, int portId, bool refresh=true
                                            // out: bool success
                                            rows = handleDisableActiveProfile(args);
                                            break;
                                        case "switchProfile":
                                            // in: int slotId, int portId, string iccid, bool enable=true, bool refresh=true
                                            // out: bool success
                                            rows = handleSwitchProfile(args);
                                            break;
                                        case "setNickname":
                                            // in: int slotId, int portId, string iccid, string nickname
                                            // out: bool success
                                            rows = handleSetNickname(args);
                                            break;
                                        default:
                                            rows = error("unknown_path");
                                            break;
                                    }
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

        String rowsJson = rowsToJson(rows);

        return row("rows", rowsJson);
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

    private MatrixCursor handleGetCards(Map<String, String> args) throws Exception
    {
        var euiccChannelManager = (DefaultEuiccChannelManager) appContainer.getEuiccChannelManager();

        var getUiccCardsMethod = DefaultEuiccChannelManager.class.getDeclaredMethod("getUiccCards");
        getUiccCardsMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        var cards = (Collection<UiccCardInfoCompat>) getUiccCardsMethod.invoke(euiccChannelManager);

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

    private MatrixCursor handleSetNickname(Map<String, String> args) throws Exception
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

    @SuppressWarnings("unchecked")
    private EuiccChannel findEuiccChannel(DefaultEuiccChannelManager euiccChannelManager, int slotId, int portId) throws Exception
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
        return withEuiccChannel
        (
            args,
            (channel, _) -> channel.getLpa().getProfiles()
        );
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
        String[] arg = new String[1];

        if (!tryGetArgAsString(args, key, arg))
            return false;

        try
        {
            out[0] = Integer.parseInt(arg[0]);
            return true;
        }
        catch (NumberFormatException ex)
        {
            return false;
        }
    }

    private static boolean tryGetArgAsBoolean(Map<String, String> args, String key, boolean[] out)
    {
        String[] arg = new String[1];

        if (!tryGetArgAsString(args, key, arg))
            return false;

        out[0] = arg[0].equals("1")
            || arg[0].toLowerCase().startsWith("y")
            || arg[0].equalsIgnoreCase("on")
            || arg[0].equalsIgnoreCase("true");

        return true;
    }

    private void requireSlotAndPort(Map<String, String> args, int[] slotIdOut, int[] portIdOut) throws Exception
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
            "isEnabled",
            "name",
            "nickname"
        };

        Object[][] rows = profiles.stream()
            .map(p -> new Object[]
            {
                p.getIccid(),
                LPAUtilsKt.isEnabled(p),
                p.getName(),
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

    private static String rowsToJson(MatrixCursor rows)
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

            outRows.add(row);
        }

        String json = new GsonBuilder()
            .serializeNulls()
            .create()
            .toJson(outRows);

        return json;
    }

    // endregion

    // region HTTP Helpers

    private void httpPostAsJson(URL url, Map<String, Object> data) throws Exception
    {
        String json = new GsonBuilder()
            .serializeNulls()
            .create()
            .toJson(data);

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
