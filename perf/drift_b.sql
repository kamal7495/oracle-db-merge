-- Makes PERF_B differ from PERF_A in known, countable ways. Run as PERF_B after trading_data.sql.
-- &1 = number of trades (same as the load).
--   TRADE:         price changed where MOD(TRADE_ID,1000)=1          -> conflicts
--                  deleted where MOD(TRADE_ID,5000)=2 (and children)  -> only in PERF_A
--                  100 new trades above &1                            -> only in PERF_B
--   TRADE_KEYWORD: 'Desk' changed where MOD(TRADE_ID,2000)=3          -> conflicts
--   schema:        new column, changed package body, dropped view, missing grant, extra backup table
WHENEVER SQLERROR EXIT FAILURE
SET VERIFY OFF
DEFINE TRADES = &1

UPDATE TRADE SET PRICE = PRICE + 0.01, VERSION_NUM = VERSION_NUM + 1 WHERE MOD(TRADE_ID, 1000) = 1;
DELETE FROM TRADE_KEYWORD WHERE MOD(TRADE_ID, 5000) = 2;
DELETE FROM BO_MESSAGE   WHERE MOD(MESSAGE_ID, 5000) = 2;
DELETE FROM TRADE_FEE    WHERE MOD(TRADE_ID, 5000) = 2;
DELETE FROM TRADE        WHERE MOD(TRADE_ID, 5000) = 2;
INSERT INTO TRADE
SELECT &TRADES + level, 'EXT-B-' || LPAD(level, 6, '0'), 1, 1, 1, DATE '2026-11-01', DATE '2026-11-03',
       1000, 100, 100000, 'USD', 'VERIFIED', 0, TIMESTAMP '2026-11-01 09:00:00', NULL
  FROM dual CONNECT BY level <= 100;
UPDATE TRADE_KEYWORD SET KEYWORD_VALUE = 'DESK_MOVED' WHERE KEYWORD_NAME = 'Desk' AND MOD(TRADE_ID, 2000) = 3;
COMMIT;

ALTER TABLE TRADE ADD (TRADER_NOTES VARCHAR2(200));
DROP VIEW V_OPEN_MESSAGES;
CREATE TABLE BKP_TRADE_2024 AS SELECT * FROM TRADE WHERE ROWNUM <= 10;

CREATE OR REPLACE PACKAGE BODY PKG_TRADE_UTIL AS
  FUNCTION trade_notional(p_trade_id IN NUMBER) RETURN NUMBER IS
    v NUMBER;
  BEGIN
    SELECT NOTIONAL INTO v FROM TRADE WHERE TRADE_ID = p_trade_id;
    RETURN v;
  END trade_notional;

  PROCEDURE cancel_trade(p_trade_id IN NUMBER) IS
  BEGIN
    -- hotfix: keep an audit trail of cancellations
    UPDATE TRADE SET TRADE_STATUS = 'CANCELED', VERSION_NUM = VERSION_NUM + 1,
                     TRADER_NOTES = 'canceled ' || TO_CHAR(SYSDATE, 'YYYY-MM-DD')
     WHERE TRADE_ID = p_trade_id;
  END cancel_trade;
END PKG_TRADE_UTIL;
/
EXIT
