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
                                            // out: ping=pong
                                            rows = handlePing(args);
                                            break;
                                        case "cards":
                                            // out (many, can be empty): slotId, portId
                                            rows = handleGetCards(args);
                                            break;
                                        case "profiles":
                                            // in: slotId, portId
                                            // out (many, can be empty): iccid, isEnabled, name, nickname
                                            rows = handleGetProfiles(args);
                                            break;
                                        case "downloadProfile":
                                            // in: (slotId, portId) AND (activationCode OR address, matchingId?, confirmationCode?) AND imei?
                                            // out (single, can be empty): iccid, isEnabled, name, nickname
                                            rows = handleDownloadProfile(args);
                                            break;
                                        case "deleteProfile":
                                            // in: slotId, portId, iccid
                                            // out: success
                                            rows = handleDeleteProfile(args);
                                            break;
                                        case "enableProfile":
                                            // in: slotId, portId, iccid, refresh(true)
                                            // out: success
                                            rows = handleEnableProfile(args);
                                            break;
                                        case "disableProfile":
                                            // in: slotId, portId, iccid, refresh(true)
                                            // out: success
                                            rows = handleDisableProfile(args);
                                            break;
                                        case "activeProfile":
                                            // in: slotId, portId
                                            // out (single, can be empty): iccid, isEnabled, name, nickname
                                            rows = handleGetActiveProfile(args);
                                            break;
                                        case "disableActiveProfile":
                                            // in: slotId, portId, refresh(true)
                                            // out (single, can be empty): iccid, isEnabled, name, nickname
                                            rows = handleDisableActiveProfile(args);
                                            break;
                                        case "switchProfile":
                                            // in: slotId, portId, iccid, enable(true), refresh(true)
                                            // out: success
                                            rows = handleSwitchProfile(args);
                                            break;
                                        case "setNickname":
                                            // in: slotId, portId, iccid, nickname
                                            // out: success
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

        return projectColumns(rows, projection, new String[] { "error" });
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
        List<LocalProfileInfo> profiles = withEuiccChannel
        (
            args,
            (channel, _) -> channel.getLpa().getProfiles()
        );

        return profiles(profiles);
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

        List<LocalProfileInfo> profilesBefore = withEuiccChannel
        (
            args,
            (channel, _) -> channel.getLpa().getProfiles()
        );

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

                                    // TODO: test if it works
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

        List<LocalProfileInfo> profilesAfter = withEuiccChannel
        (
            args,
            (channel, _) -> channel.getLpa().getProfiles()
        );

        var downloadedProfile = profilesAfter.stream()
            .filter(p -> !iccidsBefore.contains(p.getIccid()))
            .findFirst()
            .orElse(null);

        if (downloadedProfile == null)
            return empty();

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

    private MatrixCursor handleGetActiveProfile(Map<String, String> args) throws Exception
    {
        List<LocalProfileInfo> profiles = withEuiccChannel
        (
            args,
            (channel, _) -> channel.getLpa().getProfiles()
        );

        var enabledProfile = LPAUtilsKt.getEnabled(profiles);

        if (enabledProfile == null)
            return empty();

        return profile(enabledProfile); 
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

        if (iccid == null)
            return empty();

        List<LocalProfileInfo> profiles = withEuiccChannel
        (
            args,
            (channel, _) -> channel.getLpa().getProfiles()
        );

        var profile = profiles.stream()
            .filter(p -> p.getIccid().equals(iccid))
            .findFirst()
            .orElse(null); // should never be null

        if (profile == null)
            return empty();

        return profile(profile);   
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
    private <T> T withEuiccChannel(int slotId, int portId, Function2<EuiccChannel, Continuation<? super T>, ?> operation) throws Exception
    {
        var euiccChannelManager = appContainer.getEuiccChannelManager();

        return (T) BuildersKt.runBlocking
        (
            EmptyCoroutineContext.INSTANCE,
            (_, continuation) -> euiccChannelManager.withEuiccChannel(slotId, portId, operation, continuation)
        );
    }

    private <T> T withEuiccChannel(Map<String, String> args, Function2<EuiccChannel, Continuation<? super T>, ?> operation) throws Exception
    {
        var slotId = new int[1];
        var portId = new int[1];
        requireSlotAndPort(args, slotId, portId);

        return withEuiccChannel(slotId[0], portId[0], operation);
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
        String[] rowCols = rows.getColumnNames();
        Set<String> available = new LinkedHashSet<>(Arrays.asList(rowCols));
        var cols = new LinkedHashSet<String>();

        if (projection != null && projection.length > 0)
            cols.addAll(Arrays.asList(projection));
        else
            cols.addAll(available);

        if (preserve != null && preserve.length > 0)
        {
            for (String col : preserve)
            {
                Stream.of(preserve)
                    .filter(available::contains)
                    .forEach(cols::add);
            }
        }

        if (cols.isEmpty())
            return rows;

        var outCols = cols.toArray(new String[0]);
        var outRows = new MatrixCursor(outCols);

        while (rows.moveToNext())
        {
            var row = new Object[outCols.length];

            for (int i = 0; i < outCols.length; i++)
            {
                int index = rows.getColumnIndex(outCols[i]);

                if (index < 0)
                {
                    row[i] = null;
                    continue;
                }

                switch (rows.getType(index))
                {
                    case Cursor.FIELD_TYPE_NULL:
                        row[i] = null;
                        break;
                    case Cursor.FIELD_TYPE_INTEGER:
                        row[i] = rows.getLong(index);
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        row[i] = rows.getDouble(index);
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        row[i] = rows.getBlob(index);
                        break;
                    case Cursor.FIELD_TYPE_STRING:
                    default:
                        row[i] = rows.getString(index);
                        break;
                }
            }

            outRows.addRow(row);
        }

        return outRows;
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
