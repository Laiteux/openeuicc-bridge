package im.angry.openeuicc.bridge;

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
import im.angry.openeuicc.core.EuiccChannel;
import im.angry.openeuicc.core.EuiccChannelManager;
import im.angry.openeuicc.util.LPAUtilsKt;
import im.angry.openeuicc.di.AppContainer;
import net.typeblog.lpac_jni.LocalProfileInfo;

public class LpaBridgeProvider extends ContentProvider
{
    private AppContainer appContainer;

    @Override
    public boolean onCreate()
    {
        appContainer = ((OpenEuiccApplication)getContext().getApplicationContext()).getAppContainer();
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

    private MatrixCursor handleGetProfiles(Map<String, String> args) throws Exception
    {
        List<LocalProfileInfo> profiles = withEuiccChannel(
            args,
            (channel, _) -> channel.getLpa().getProfiles()
        );

        if (profiles == null || profiles.isEmpty())
            return empty();

        var rows = new MatrixCursor(new String[]
        {
            "iccid",
            "state",
            "name",
            "nickname"
        });

        for (LocalProfileInfo profile : profiles)
        {
            rows.addRow(new Object[] {
                profile.getIccid(),
                profile.getState().toString(), // TODO: replace by LPAUtilsKt.isEnabled(profile)?
                profile.getName(),
                LPAUtilsKt.getDisplayName(profile)
            });
        }

        return rows;
    }

    // endregion

    // region LPA Helpers

    @SuppressWarnings("unchecked")
    private <T> T withEuiccChannel(Map<String, String> args, Function2<EuiccChannel, Continuation<? super T>, ?> operation) throws Exception
    {
        final String slotIdArg = "slotId";
        final String portIdArg = "portId";

        var slotId = new int[1];
        var portId = new int[1];

        if (!tryGetInt(args, slotIdArg, slotId))
            throw new Exception("missing_arg_" + slotIdArg);

        if (!tryGetInt(args, portIdArg, portId))
            throw new Exception("missing_arg_" + portIdArg);

        EuiccChannelManager channelManager = appContainer.getEuiccChannelManager();

        return (T)BuildersKt.runBlocking(
            EmptyCoroutineContext.INSTANCE,
            (scope, continuation) -> {
                return channelManager.withEuiccChannel(
                    slotId[0],
                    portId[0],
                    operation,
                    continuation
                );
            }
        );
    }

    // endregion

    // region Arg Helpers

    private static Map<String, String> getArgsFromUri(Uri uri)
    {
        var out = new HashMap<String, String>();

        for (String name : uri.getQueryParameterNames())
        {
            out.put(name, uri.getQueryParameter(name));
        }
    
        return out;
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
        String[] tmp = new String[1];

        if (!tryGet(args, key, tmp))
            return false;

        try
        {
            out[0] = Integer.parseInt(tmp[0]);
            return true;
        }
        catch (NumberFormatException ex)
        {
            return false;
        }
    }

    // endregion

    // region Row Helpers

    private static MatrixCursor rows(String[] columns, Object... values)
    {
        var rows = new MatrixCursor(columns);
        rows.addRow(values);
        return rows;
    }

    private static MatrixCursor row(String key, String value)
    {
        return rows(new String[] { key }, value);
    }

    private static MatrixCursor error(String message)
    {
        return row("error", message);
    }

    private static MatrixCursor empty()
    {
        return new MatrixCursor(new String[0]);
    }

    private static MatrixCursor projectColumns(MatrixCursor source, String[] projection)
    {
        return projectColumns(source, projection, null);
    }

    private static MatrixCursor projectColumns(MatrixCursor source, String[] projection, String[] preserve)
    {
        if (source == null)
            return empty();

        String[] sourceCols = source.getColumnNames();
        var cols = new LinkedHashSet<String>();

        if (projection != null && projection.length > 0)
            Collections.addAll(cols, projection);
        else
            Collections.addAll(cols, sourceCols);

        if (preserve != null && preserve.length > 0)
        {
            for (String col : preserve)
            {
                if (col == null)
                    continue;

                boolean exists = false;

                for (String sourceCol : sourceCols)
                {
                    if (col.equals(sourceCol))
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
            return source;

        var outCols = cols.toArray(new String[0]);
        var out = new MatrixCursor(outCols);

        while (source.moveToNext())
        {
            var row = new Object[outCols.length];

            for (int i = 0; i < outCols.length; i++)
            {
                int index = source.getColumnIndex(outCols[i]);

                if (index < 0)
                {
                    row[i] = null;
                    continue;
                }

                switch (source.getType(index))
                {
                    case Cursor.FIELD_TYPE_NULL:
                        row[i] = null;
                        break;
                    case Cursor.FIELD_TYPE_INTEGER:
                        row[i] = source.getLong(index);
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        row[i] = source.getDouble(index);
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        row[i] = source.getBlob(index);
                        break;
                    case Cursor.FIELD_TYPE_STRING:
                    default:
                        row[i] = source.getString(index);
                        break;
                }
            }

            out.addRow(row);
        }

        return out;
    }

    // endregion
}
