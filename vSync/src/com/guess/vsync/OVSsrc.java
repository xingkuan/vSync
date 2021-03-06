package com.guess.vsync;

import java.io.*;
import java.util.*;
import java.text.*;
import java.sql.*;
import oracle.jdbc.*;
import oracle.jdbc.pool.OracleDataSource;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/*
 class OVSsrc
 
 OVSsrc handles all db interaction with the source database/table
   -  establishes the db connection
   -  turns log trigger on/off
   -  commits/rolls back  transactions on the log table
   -  initializes the data source query 
   -  initializes the data source recordset
   -  exposes the data source recordset object (to be passed to the OVStgt class for data load)
   -  wraps all OVSsrc and OVStgt functions and exposes them as necessary
  
*/

class OVSsrc {
   private OVScred srcCred;
   private OVSmeta tblMeta;
   private Connection srcConn;
   private Statement srcStmt;
   private boolean srcConnOpen;
   private boolean srcStmtOpen;
   private ResultSet sRset;
   private int tableID;
   private boolean recover=false;
   private int currState=0;
   private boolean isError=false;
   private int fldCnt;
   private int[] xformType;
   private String[] xformFctn;
   private int logCnt;
   private String label;
//.   private OVSlogger ovLogger;
//   private Logger ovLogger;
   
   private int connAtmptLim=5;
   private int AtmptDelay=5000;
   
   private static final Logger ovLogger = LogManager.getLogger();
   
//.   public void setLogger(OVSlogger ol) {
//.      ovLogger=ol;
//.   }
//   public void setLogger(Logger ol) {
//	      ovLogger=ol;
//	   }

   public boolean init() {
      label=">";
      return linit();
   }
   public boolean init(String lbl) {
      label=lbl;
      return linit();
   }

   private boolean linit() {
      int attempts;
      //  initializes the connection
      
      // initialize variables
      isError=false;
      currState=0;
	  boolean rtv = true;
     srcConnOpen=false;
     srcStmtOpen=false;
     

      //test for db type oracle and if it is load oracle driver
      srcCred=tblMeta.getSrcCred();
      if (srcCred.getType() ==1) {
         try {
            Class.forName("oracle.jdbc.OracleDriver"); 
         } catch(ClassNotFoundException e){
            //System.err.println(label + " Driver error has occured");
//.            ovLogger.log(label + " Driver error has occured");
            ovLogger.error(label + " Driver error has occured");
            e.printStackTrace();
	         rtv = false;
            return rtv;
         }
      } else {
         //System.err.println(label + " source db type not supported");
//.         ovLogger.log(label + " source db type not supported");
         ovLogger.error(label + " source db type not supported");
         rtv=false;
         return rtv;
      }
      
      attempts=0;
      while (attempts<connAtmptLim ) {
         attempts++;
         
      try {
//.         ovLogger.log(label + " conn attempt " + attempts);
         ovLogger.info(label + " conn attempt " + attempts);
         // this attempts a reset from a prior exception
         close();
         //establish Oracle connection
         srcConn = DriverManager.getConnection(srcCred.getURL(), srcCred.getUser(), srcCred.getPWD());
         srcConnOpen=true;
         srcConn.setAutoCommit(false);
         srcStmt = srcConn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
         srcStmtOpen=true;
         // all success, burn rest of attempts
         attempts=connAtmptLim;
      } catch(SQLException e) {
         //System.out.println(label + " tgt cannot connect to db");
         //System.out.println(label + e.getMessage());
//.         ovLogger.log(label + " src cannot connect to db - init failed ");
         ovLogger.error(label + " src cannot connect to db - init failed ");
//.         ovLogger.log(label + e.getMessage());
         ovLogger.error(label + e.getMessage());
         rtv=false;
         msWait(AtmptDelay);
      }
      
      }

      return rtv;
   }
   public boolean initSrcQuery(String whereClause){
      // initializes the source recordset using the passed parameter whereClause as the where clause 
      boolean rtv=true;
      
      try {
         sRset=srcStmt.executeQuery(tblMeta.getSQLSelect() + " " + whereClause);
      } catch(SQLException e) {
         ovLogger.error(label + " recordset not created");
         ovLogger.error(e);
         ovLogger.error(label + " \n\n\n" + tblMeta.getSQLSelect() + " " + whereClause + "\n\n\n");
         rtv=false;
      }
      return rtv;
   }
   public boolean initSrcLogQuery() {
      // initializes the source log query
      boolean rtv=true;
      
      try {
         // set snaptime, count the number of records in the log table, then create recordset
    	 String sqlStmt = "update " + tblMeta.getSrcSchema() + "." +  tblMeta.getLogTable()  + " set snaptime  = to_date('01-JUN-1910')  where  snaptime != '01-JUN-1910' ";
         srcStmt.executeUpdate(sqlStmt);
         sRset  = srcStmt.executeQuery( " select count(distinct M_ROW)   from   "  + tblMeta.getSrcSchema() + "." +  tblMeta.getLogTable() + " where  snaptime = '01-JUN-1910'  ");
         sRset.next();
         logCnt = Integer.parseInt(sRset.getString(1));
         sRset.close();
         sRset=srcStmt.executeQuery("select distinct M_ROW from " +   tblMeta.getSrcSchema() + "." +  tblMeta.getLogTable() + " where   snaptime = '01-JUN-1910'  ");
      } catch(SQLException e) {
         ovLogger.error("src init failure" + e);
         rtv=false;
      }
      return rtv;
   }
   
   public int getThreshLogCount() {
      // counts and returns the number of records in the source log table
      
      int lc=0;
      try {
         // set snaptime, count the number of records in the log table, then create recordset
         
         sRset  = srcStmt.executeQuery( " select count(distinct M_ROW)   from   "  + tblMeta.getSrcSchema() + "." +  tblMeta.getLogTable() );
         sRset.next();
         lc = Integer.parseInt(sRset.getString(1));
         sRset.close();
      } catch(SQLException e) {
         //System.out.println(label + " error during threshlogcnt");
//.         ovLogger.log(label + " error during threshlogcnt");
         ovLogger.error(label + " error during threshlogcnt");
      }
      //System.out.println(label + " theshold log count: " + lc);
//.      ovLogger.log(label + " theshold log count: " + lc);
      ovLogger.info(label + " theshold log count: " + lc);
      return lc;
   }
   
   public int getRecordCount(){
      // counts and returns the number of records in the source table
      
      int rtv;
    //  Connection lConn;
    //  Statement lStmt;
      ResultSet lrRset;
      int i;

      rtv=0;
      try {
     //should use the srcConn and srcStmt
     //	  lConn = DriverManager.getConnection(srcCred.getURL(), srcCred.getUser(), srcCred.getPWD());
     //   lStmt = lConn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    	  srcStmt = srcConn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

     //   lrRset=lStmt.executeQuery("select count(*) from " + tblMeta.getSrcSchema() + "." + tblMeta.getSrcTable());
         lrRset=srcStmt.executeQuery("select count(*) from " + tblMeta.getSrcSchema() + "." + tblMeta.getSrcTable());
         if (lrRset.next()) {
            rtv = Integer.parseInt(lrRset.getString(1));  
         }
         lrRset.close();
     //    lStmt.close();
     //    lConn.close();
      } catch(SQLException e) {
         //System.out.println(label + " error during src audit"); 
//.         ovLogger.log(label + " error during src audit"); 
         ovLogger.error(label + " error during src audit: "+ e); 
      }
      return rtv;
   }
   public void setTriggerOn() throws SQLException {
      srcStmt.executeUpdate("alter trigger "  + tblMeta.getSrcTrigger() + " enable");    
      //System.out.println("========>>> trigger turned on");      
   }
   public int getLogCnt() {
      return logCnt;
   }
   public void setTriggerOff() throws SQLException {
      srcStmt.executeUpdate("alter trigger "  + tblMeta.getSrcTrigger() + " disable");       
      //System.out.println("========>>> trigger turned off");      
   }
   public void truncateLog() throws SQLException {
      //System.out.println("truncate table " + tblMeta.getLogTable());
      srcStmt.executeUpdate("truncate table " + tblMeta.getSrcSchema() + "." +  tblMeta.getLogTable());
   }
   public void delConsumedLog() throws SQLException {
      srcStmt.executeUpdate(" DELETE FROM " +  tblMeta.getSrcSchema() + "." +  tblMeta.getLogTable() +  " where  snaptime = '01-JUN-1910' "); 
   }
   public ResultSet getSrcResultSet() {
      return sRset;
   }
   public void closeSrcResultSet() throws SQLException {
       sRset.close();
   }
   public void OVSsrc(OVScred ovsc) {
      srcCred=ovsc;
   }
   public void setRecover(boolean rcvr) {
      recover=rcvr;
   }
   public void setCred(OVScred ovsc) {
      srcCred=ovsc;
   }
   public void setMeta(OVSmeta mta) {
      tblMeta=mta;
   }
   public void commit() throws SQLException {
      srcConn.commit();
   }
   public void rollback() throws SQLException {
      srcConn.rollback();
   }
   public void close() throws SQLException {
      if (srcStmtOpen) {
         srcStmt.close();
         srcStmtOpen=false;
      }
      if (srcConnOpen) {
         srcConn.close();
         srcConnOpen=false;
      }
      ovLogger.info(label + " closed src db src");
   }
   private  void msWait(int mSecs) {
      try {
         Thread.sleep(mSecs);
      } catch (InterruptedException e) {
      }
   }
}