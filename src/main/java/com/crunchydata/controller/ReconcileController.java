package com.crunchydata.controller;

import com.crunchydata.model.ColumnInfo;
import com.crunchydata.util.Logging;
import com.crunchydata.util.ThreadSync;
import com.crunchydata.services.*;
import org.json.JSONObject;

import javax.sql.rowset.CachedRowSet;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static com.crunchydata.util.DatabaseUtility.getColumnInfo;
import static com.crunchydata.util.Settings.Props;

public class ReconcileController {

    public static JSONObject reconcileData(Connection repoConn, Connection sourceConn, Connection targetConn, String sourceSchema, String sourceTable, String targetSchema, String targetTable, String tableFilter, String modColumn, Integer parallelDegree, Boolean sameRDBMSOptimization, long rid, Boolean check, Integer batchNbr) {

        /////////////////////////////////////////////////
        // Variables
        /////////////////////////////////////////////////
        ThreadSync ts;
        List<dbReconcile> compareList = new ArrayList<>();
        List<dbReconcileObserver> observerList = new ArrayList<>();

        dbReconcileObserver rot;
        dbReconcile cst;
        dbReconcile ctt;

        String sqlUpdateStatus = """
                                 UPDATE dc_result SET missing_source_cnt=?, missing_target_cnt=?, not_equal_cnt=?, status=?
                                 WHERE cid=?
                                 RETURNING equal_cnt, missing_source_cnt, missing_target_cnt, not_equal_cnt, status
                                 """;

        String sqlMarkNESource = """
                                 UPDATE dc_source s SET compare_result = 'n'
                                 WHERE s.table_name=?
                                       AND EXISTS (SELECT 1 FROM dc_target t WHERE t.table_name=? AND s.pk_hash=t.pk_hash AND s.column_hash != t.column_hash)
                                 """;

        String sqlMarkNETarget ="""
                                UPDATE dc_target t SET compare_result = 'n'
                                WHERE t.table_name=?
                                      AND EXISTS (SELECT 1 FROM dc_source s WHERE s.table_name=? AND t.pk_hash=s.pk_hash AND t.column_hash != s.column_hash)
                                """;

        String sqlMarkMissingSource = """
                                      UPDATE dc_target t SET compare_result = 'm'
                                      WHERE t.table_name=?
                                            AND NOT EXISTS (SELECT 1 FROM dc_source s WHERE s.table_name=? AND t.pk_hash=s.pk_hash)
                                      """;

        String sqlMarkMissingTarget = """
                                      UPDATE dc_source s SET compare_result = 'm'
                                      WHERE s.table_name=?
                                            AND NOT EXISTS (SELECT 1 FROM dc_target t WHERE t.table_name=? AND s.pk_hash=t.pk_hash)
                                      """;



        /////////////////////////////////////////////////
        // Get Column Info
        /////////////////////////////////////////////////
        ArrayList<Object> binds = new ArrayList<>();
        JSONObject result = new JSONObject();
        result.put("tableName", targetTable);
        result.put("status","failed");
        result.put("compareStatus","failed");

        ColumnInfo ci = getColumnInfo(Props.getProperty("target-type"), targetConn, targetSchema, targetTable, sameRDBMSOptimization);

        Logging.write("info", "reconcile-controller", "Columns: " + ci.columnList);

        Integer cid = RepoController.dcrCreate(repoConn, targetTable, rid);

        ////////////////////////////////////////
        // Set Source & Target Variables
        ////////////////////////////////////////
        String sqlSource = "";
        String sqlTarget = "";

        sqlSource = switch (Props.getProperty("source-type")) {
            case "postgres" ->
                    dbPostgres.buildLoadSQL(sameRDBMSOptimization, sourceSchema, sourceTable, ci.pgPK, ci.pkJSON, ci.pgColumn, tableFilter);
            case "oracle" ->
                    dbOracle.buildLoadSQL(sameRDBMSOptimization, sourceSchema, sourceTable, ci.oraPK, ci.pkJSON, ci.oraColumn, tableFilter);
            default -> sqlSource;
        };

        sqlTarget = switch (Props.getProperty("target-type")) {
            case "postgres" ->
                    dbPostgres.buildLoadSQL(sameRDBMSOptimization, targetSchema, targetTable, ci.pgPK, ci.pkJSON, ci.pgColumn, tableFilter);
            case "oracle" ->
                    dbOracle.buildLoadSQL(sameRDBMSOptimization, targetSchema, targetTable, ci.oraPK, ci.pkJSON, ci.oraColumn, tableFilter);
            default -> sqlTarget;
        };

        Logging.write("info", "reconcile-controller", "Source Compare Hash SQL: " + sqlSource);
        Logging.write("info", "reconcile-controller", "Target Compare Hash SQL: " + sqlTarget);


        if (check) {
            dbReconcileCheck.recheckRows(repoConn, sqlSource, sqlTarget, sourceConn, targetConn, sourceSchema, sourceTable, targetSchema, targetTable, ci, sameRDBMSOptimization, batchNbr, cid);
        } else {
            ////////////////////////////////////////
            // Execute Compare SQL on Source and Target
            ////////////////////////////////////////
            if (ci.pkList.isBlank() || ci.pkList.isEmpty()) {
                Logging.write("warning", "reconcile-controller", "Table " + targetTable + " has no Primary Key, skipping reconciliation");
                result.put("status", "skipped");
                binds.clear();
                binds.add(0,cid);
                dbPostgres.simpleUpdate(repoConn,"UPDATE dc_result SET equal_cnt=0,missing_source_cnt=0,missing_target_cnt=0,not_equal_cnt=0,source_cnt=0,target_cnt=0,status='skipped' WHERE cid=?",binds, true);
            } else {
                Logging.write("info", "reconcile-controller", "Starting compare hash threads");

                for (Integer i = 0; i < parallelDegree; i++) {
                    ts = new ThreadSync();
                    rot = new dbReconcileObserver(targetSchema, targetTable, cid, ts, i, batchNbr);
                    rot.start();
                    observerList.add(rot);
                    cst = new dbReconcile(i, "source", sqlSource, tableFilter, modColumn, parallelDegree, sourceSchema, sourceTable, ci.nbrColumns, ci.nbrPKColumns, cid, ts, ci.pkList, sameRDBMSOptimization, batchNbr);
                    cst.start();
                    compareList.add(cst);
                    ctt = new dbReconcile(i, "target", sqlTarget, tableFilter, modColumn, parallelDegree, targetSchema, targetTable, ci.nbrColumns, ci.nbrPKColumns, cid, ts, ci.pkList, sameRDBMSOptimization, batchNbr);
                    ctt.start();
                    compareList.add(ctt);
                    try {
                        Thread.sleep(2000);
                    } catch (Exception e) {
                    }
                }

                try {
                    Logging.write("info", "reconcile-controller", "Waiting for hash threads to complete");
                    ////////////////////////////////////////////////////////////////
                    // Check Threads
                    ////////////////////////////////////////////////////////////////
                    for (dbReconcile thread : compareList) {
                        thread.join();
                    }
                    Logging.write("info", "reconcile-controller", "Waiting for reconcile threads to complete");
                    for (dbReconcileObserver thread : observerList) {
                        thread.join();
                    }

                } catch (Exception e) {
                    Logging.write("severe", "reconcile-controller", "Error in thread");
                }

            }
        }

        ////////////////////////////////////////
        // Summarize Results
        ////////////////////////////////////////
        try { dbPostgres.simpleExecute(repoConn,"set enable_nestloop='off'"); } catch (Exception e) {}
        binds.clear();
        binds.add(0,targetTable);
        binds.add(1,targetTable);

        Logging.write("info", "reconcile-controller", "Analyzing: Step 1 of 3 - Missing on Source");
        Integer missingSource = dbPostgres.simpleUpdate(repoConn, sqlMarkMissingSource, binds, true);

        Logging.write("info", "reconcile-controller", "Analyzing: Step 2 of 3 - Missing on Target");
        Integer missingTarget = dbPostgres.simpleUpdate(repoConn, sqlMarkMissingTarget, binds, true);

        Logging.write("info", "reconcile-controller", "Analyzing: Step 3 of 3 - Note Equal");
        Integer notEqual = dbPostgres.simpleUpdate(repoConn, sqlMarkNESource, binds, true);

        dbPostgres.simpleUpdate(repoConn, sqlMarkNETarget, binds, true);

        try {
            result.put("missingSource",missingSource);
            result.put("missingTarget",missingTarget);
            result.put("notEqual",notEqual);
            result.put("compareStatus",(missingSource+missingTarget+notEqual > 0) ? "out-of-sync" : "in-sync");

            ///////////////////////////////////////////////////////
            // Update and Check Status
            ///////////////////////////////////////////////////////
            binds.clear();
            binds.add(0,missingSource);
            binds.add(1,missingTarget);
            binds.add(2,notEqual);
            binds.add(3, result.getString("compareStatus"));
            binds.add(4, cid);
            CachedRowSet crsResult = dbPostgres.simpleUpdateReturning(repoConn, sqlUpdateStatus, binds);

            while (crsResult.next()) {
                result.put("equal", crsResult.getInt(1));
            }

        } catch (Exception e) {
            Logging.write("severe", "reconcile-controller", "Error analyzing compare: " + e.getMessage());
        }

        Logging.write("info", "reconcile-controller", "Reconciliation Complete:  Table = " + targetTable +"; Equal = " + result.getInt("equal") + "; Not Equal = " + result.getInt("notEqual") + "; Missing Source = " + result.getInt("missingSource") + "; Missing Target = " + result.getInt("missingTarget"));

        result.put("status", "success");

        return result;

    }


}