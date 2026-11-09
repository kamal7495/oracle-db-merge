-- Fills the trading schema with deterministic synthetic data. Run as the schema owner.
-- &1 = number of trades, &2 = offset added to trade ids (0 for PERF_A/PERF_B; PERF_C gets trades no other schema has).
-- Keywords: 3 per trade. Messages: 1 per trade. Fees: 1 per 10 trades. Static data is the same in every schema.
WHENEVER SQLERROR EXIT FAILURE
SET VERIFY OFF
DEFINE TRADES = &1
DEFINE OFFSET = &2

ALTER SESSION ENABLE PARALLEL DML;

INSERT /*+ APPEND */ INTO LEGAL_ENTITY
SELECT level, 'LE' || LPAD(level, 6, '0'), 'Legal entity ' || level,
       CASE MOD(level, 4) WHEN 0 THEN 'GBR' WHEN 1 THEN 'USA' WHEN 2 THEN 'IND' ELSE 'AUS' END,
       'LEI' || LPAD(level, 17, '0')
  FROM dual CONNECT BY level <= 5000;
COMMIT;

INSERT /*+ APPEND */ INTO BOOK
SELECT level, 'BOOK_' || LPAD(level, 5, '0'), MOD(level * 7, 5000) + 1,
       CASE MOD(level, 3) WHEN 0 THEN 'USD' WHEN 1 THEN 'EUR' ELSE 'GBP' END, 1
  FROM dual CONNECT BY level <= 2000;
COMMIT;

INSERT /*+ APPEND */ INTO BOOK_ATTRIBUTE
SELECT b.BOOK_ID, a.n, 'value ' || b.BOOK_ID
  FROM BOOK b CROSS JOIN (SELECT 'DESK' n FROM dual UNION ALL SELECT 'REGION' FROM dual) a;
COMMIT;

INSERT /*+ APPEND */ INTO PRODUCT_DESC
SELECT level, CASE MOD(level, 5) WHEN 0 THEN 'Bond' WHEN 1 THEN 'Swap' WHEN 2 THEN 'FXForward' WHEN 3 THEN 'Repo' ELSE 'Equity' END,
       'Product ' || level, CASE MOD(level, 3) WHEN 0 THEN 'USD' WHEN 1 THEN 'EUR' ELSE 'GBP' END,
       DATE '2026-01-01' + MOD(level, 3650)
  FROM dual CONNECT BY level <= 200000;
COMMIT;

INSERT /*+ APPEND */ INTO PRODUCT_BOND
SELECT PRODUCT_ID, MOD(PRODUCT_ID * 13, 5000) + 1, ROUND(MOD(PRODUCT_ID, 800) / 100, 6), 1000000
  FROM PRODUCT_DESC WHERE PRODUCT_TYPE = 'Bond';
COMMIT;

-- trades: generator from a 1000-row cross join, so CONNECT BY never builds a huge in-memory set
INSERT /*+ APPEND */ INTO TRADE
SELECT n, 'EXT-' || LPAD(n, 10, '0'), MOD(n * 31, 2000) + 1, MOD(n * 17, 5000) + 1, MOD(n * 7, 200000) + 1,
       DATE '2024-01-01' + MOD(n, 900), DATE '2024-01-03' + MOD(n, 900),
       MOD(n, 1000) * 1000 + 1000, ROUND(90 + MOD(n, 2000) / 100, 8), (MOD(n, 1000) * 1000 + 1000) * (90 + MOD(n, 2000) / 100),
       CASE MOD(n, 3) WHEN 0 THEN 'USD' WHEN 1 THEN 'EUR' ELSE 'GBP' END,
       CASE WHEN MOD(n, 50) = 0 THEN 'CANCELED' WHEN MOD(n, 7) = 0 THEN 'PENDING' ELSE 'VERIFIED' END,
       MOD(n, 4), TIMESTAMP '2024-01-01 08:00:00' + NUMTODSINTERVAL(MOD(n, 900 * 86400), 'SECOND'),
       -- chains of up to 3 amendments: ...1 -> ...0, ...2 -> ...1, ...3 -> ...2 (none under the trades drift_b deletes)
       CASE WHEN MOD(n, 10) IN (1, 2, 3) AND MOD(n, 5000) <> 3 AND n - 1 > &OFFSET THEN n - 1 END
  FROM (SELECT (a.r - 1) * 1000 + b.r + &OFFSET AS n
          FROM (SELECT level r FROM dual CONNECT BY level <= CEIL(&TRADES / 1000)) a
         CROSS JOIN (SELECT level r FROM dual CONNECT BY level <= 1000) b)
 WHERE n <= &TRADES + &OFFSET;
COMMIT;

INSERT /*+ APPEND */ INTO TRADE_KEYWORD
SELECT t.TRADE_ID, k.n,
       CASE k.n WHEN 'Desk' THEN 'DESK_' || MOD(t.TRADE_ID, 20) WHEN 'Trader' THEN 'TR' || MOD(t.TRADE_ID, 300)
                ELSE 'SRC' || MOD(t.TRADE_ID, 5) END
  FROM TRADE t CROSS JOIN (SELECT 'Desk' n FROM dual UNION ALL SELECT 'Trader' FROM dual UNION ALL SELECT 'Source' FROM dual) k;
COMMIT;

INSERT /*+ APPEND */ INTO BO_MESSAGE
SELECT TRADE_ID, CASE WHEN MOD(TRADE_ID, 10) = 0 THEN MOD(TRADE_ID, 2000) + 1 ELSE TRADE_ID END,
       CASE WHEN MOD(TRADE_ID, 10) = 0 THEN 'Book' ELSE 'Trade' END,
       CASE MOD(TRADE_ID, 3) WHEN 0 THEN 'CONFIRM' WHEN 1 THEN 'PAYMENTMSG' ELSE 'SWIFT_MT300' END,
       CASE WHEN MOD(TRADE_ID, 9) = 0 THEN 'PENDING' ELSE 'SENT' END, ENTERED_TIME + INTERVAL '5' MINUTE
  FROM TRADE;
COMMIT;

INSERT /*+ APPEND */ INTO TRADE_FEE
SELECT TRADE_ID, TRADE_ID, CASE MOD(TRADE_ID, 2) WHEN 0 THEN 'BROKERAGE' ELSE 'CLEARING' END, MOD(TRADE_ID, 500) + 10
  FROM TRADE WHERE MOD(TRADE_ID, 10) = 0;
COMMIT;

INSERT /*+ APPEND */ INTO BKP_TRADE_2023 SELECT * FROM TRADE WHERE ROWNUM <= 1000;
COMMIT;

BEGIN
  DBMS_STATS.GATHER_SCHEMA_STATS(USER, degree => 2);
END;
/
EXIT
