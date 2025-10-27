package im.angry.openeuicc.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.HashMap;

public class LpaBridgeProvider extends ContentProvider
{
    public static final String AUTHORITY = "lpa.bridge";

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
            final Map<String, String> args = getArgsFromUri(uri);

            switch (path)
            {
                case "test":
                    rows = handleTest(args);
                    break;
                default:
                    rows = error("unknown_path");
                    break;
            }
        }

        return projectColumns(rows, projection, new String[] { "error" });
    }

    // region Mandatory Overrides

    @Override
    public boolean onCreate() { return true; }

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

    private MatrixCursor handleTest(Map<String, String> args)
    {
        var slotId = new int[1];
        var portId = new int[1];

        var error = requireSlotAndPort(args, slotId, portId);
        if (error != null) return error;

        return row("ok", "true");
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

    private MatrixCursor requireSlotAndPort(Map<String, String> args, int[] slotIdOut, int[] portIdOut)
    {
        final String slotIdArg = "slotId";
        final String portIdArg = "portId";

        if (!tryGetInt(args, slotIdArg, slotIdOut))
            return error("missing_arg_" + slotIdArg);

        if (!tryGetInt(args, portIdArg, portIdOut))
            return error("missing_arg_" + portIdArg);

        return null;
    }

    // endregion

    // region Row Helpers

    private static MatrixCursor empty()
    {
        return new MatrixCursor(new String[0]);
    }

    private static MatrixCursor error(String message)
    {
        return row("error", message);
    }

    private static MatrixCursor row(String key, String value)
    {
        return row(new String[] { key }, value);
    }

    private static MatrixCursor row(String[] columns, Object... values)
    {
        var rows = new MatrixCursor(columns);
        rows.addRow(values);
        return rows;
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
