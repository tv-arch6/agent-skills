package com.Cigram.vid;

import org.json.JSONObject;

import java.util.List;

/**
 * Read-only view of Cigram's own library (the catalog the admin app publishes). The player engine
 * reaches the existing catalog code only through this interface, so it can be tested without the
 * app and never writes to the library.
 */
public interface CigramLibraryData {
    /** Every movie / series item of the library. */
    List<JSONObject> items();

    /** All episodes of a series item, in any order (the engine sorts them itself). */
    List<JSONObject> episodesOf(JSONObject series);
}
