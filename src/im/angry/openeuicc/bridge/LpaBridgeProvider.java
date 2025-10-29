package im.angry.openeuicc.bridge;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import im.angry.openeuicc.OpenEuiccApplication;
import im.angry.openeuicc.core.DefaultEuiccChannelManager;
import im.angry.openeuicc.core.EuiccChannel;
import im.angry.openeuicc.core.EuiccChannelManager;
import im.angry.openeuicc.util.LPAUtilsKt;
import im.angry.openeuicc.util.UiccCardInfoCompat;
import im.angry.openeuicc.util.UiccPortInfoCompat;
import im.angry.openeuicc.di.AppContainer;
import net.typeblog.lpac_jni.LocalProfileInfo;

public class LpaBridgeProvider extends ContentProvider
{
    private AppContainer appContainer;

    @Override
    public boolean onCreate()
    {
        appContainer = ((OpenEuiccApplication) getContext().getApplicationContext()).getAppContainer();
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder)
    {
        MatrixCursor rows;

        final String path = uri.getLastPathSegment();

        if (path == null)
        {
            rows = error("no_path");
        }
        else
        {
            try
            {
                final Map<String, String> args = getArgsFromUri(uri);

                switch (path)
                {
                    case "ping":
                        rows = handlePing(args);
                        break;
                    case "cards":
                        rows = handleGetCards(args);
                        break;
                    case "profiles":
                        rows = handleGetProfiles(args);
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

        var findEuiccChannelByPortMethod = DefaultEuiccChannelManager.class.getDeclaredMethod("findEuiccChannelByPort", int.class, int.class, Continuation.class);
        findEuiccChannelByPortMethod.setAccessible(true);

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

                @SuppressWarnings("unchecked")
                var euiccChannel = (EuiccChannel) BuildersKt.runBlocking
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

        var rows = new MatrixCursor(new String[]
        {
            "iccid",
            "isEnabled",
            "displayName"
        });

        for (LocalProfileInfo profile : profiles)
        {
            rows.addRow(new Object[]
            {
                profile.getIccid(),
                LPAUtilsKt.isEnabled(profile),
                LPAUtilsKt.getDisplayName(profile)
            });
        }

        return rows;
    }

    // endregion

    // region LPA Helpers

    private <T> T withEuiccChannel(Map<String, String> args, Function2<EuiccChannel, Continuation<? super T>, ?> operation) throws Exception
    {
        var slotId = new int[1];
        var portId = new int[1];
        requireSlotAndPort(args, slotId, portId);

        return withEuiccChannel(slotId[0], portId[0], operation);
    }

    @SuppressWarnings("unchecked")
    private <T> T withEuiccChannel(int slotId, int portId, Function2<EuiccChannel, Continuation<? super T>, ?> operation) throws Exception
    {
        var euiccChannelManager = appContainer.getEuiccChannelManager();

        return (T) BuildersKt.runBlocking
        (
            EmptyCoroutineContext.INSTANCE,
            (scope, continuation) ->
            {
                return euiccChannelManager.withEuiccChannel
                (
                    slotId,
                    portId,
                    operation,
                    continuation
                );
            }
        );
    }

    // endregion

    // region Arg Helpers

    // TODO: decode?
    private static Map<String, String> getArgsFromUri(Uri uri)
    {
        var args = new HashMap<String, String>();

        for (String name : uri.getQueryParameterNames())
        {
            args.put(name, uri.getQueryParameter(name));
        }
    
        return args;
    }

    private static boolean tryGet(Map<String, String> args, String key, String[] out)
    {
        String arg = args.get(key);

        if (arg == null || arg.isEmpty())
            return false;

        out[0] = arg;
        return true;
    }

    private static boolean tryGetInt(Map<String, String> args, String key, int[] out)
    {
        String[] arg = new String[1];

        if (!tryGet(args, key, arg))
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

    private void requireSlotAndPort(Map<String, String> args, int[] slotIdOut, int[] portIdOut) throws Exception
    {
        final String slotIdArg = "slotId";
        final String portIdArg = "portId";

        if (!tryGetInt(args, slotIdArg, slotIdOut))
            throw new Exception("missing_arg_" + slotIdArg);

        if (!tryGetInt(args, portIdArg, portIdOut))
            throw new Exception("missing_arg_" + portIdArg);
    }

    // endregion

    // region Row Helpers

    private static MatrixCursor rows(String[] columns, Object... values)
    {
        var rows = new MatrixCursor(columns);
        rows.addRow(values);
        return rows;
    }

    private static MatrixCursor row(String column, String value)
    {
        return rows(new String[] { column }, value);
    }

    private static MatrixCursor error(String message)
    {
        return row("error", message);
    }

    private static MatrixCursor empty()
    {
        return new MatrixCursor(new String[0]);
    }

    private static MatrixCursor projectColumns(MatrixCursor rows, String[] projection)
    {
        return projectColumns(rows, projection, null);
    }

    private static MatrixCursor projectColumns(MatrixCursor rows, String[] projection, String[] preserve)
    {
        String[] rowCols = rows.getColumnNames();
        var cols = new LinkedHashSet<String>();

        if (projection != null && projection.length > 0)
            Collections.addAll(cols, projection);
        else
            Collections.addAll(cols, rowCols);

        if (preserve != null && preserve.length > 0)
        {
            for (String col : preserve)
            {
                boolean exists = false;

                for (String rowCol : rowCols)
                {
                    if (col.equals(rowCol))
                    {
                        exists = true;
                        break;
                    }
                }

                if (exists)
                    cols.add(col);
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
}
