-- Sample schema B (a source). Built to differ from schema A and to collide with its data:
--   * PK collisions:       region 2, customer 1, orders 1, order_item 1, product 2 (same key, different business record)
--   * identical duplicates: region 1, product 1, customer 'bob@example.com'
--   * same business key, different PK and data: customer 'alice@example.com' (id 3 here, id 1 in A)
--   * structural differences: see the comments on each object.
CREATE SEQUENCE seq_customer START WITH 100 INCREMENT BY 5 NOCACHE   -- increment differs from A
/
CREATE SEQUENCE seq_order START WITH 1000 INCREMENT BY 1 NOCACHE
/
CREATE TABLE region (
    region_id NUMBER(5) PRIMARY KEY,
    name      VARCHAR2(50) NOT NULL
)
/
CREATE TABLE product (
    product_id NUMBER(10) PRIMARY KEY,
    sku        VARCHAR2(30) NOT NULL,
    name       VARCHAR2(100) NOT NULL,
    price      NUMBER(12,2) DEFAULT 0 NOT NULL,                       -- precision differs from A
    CONSTRAINT uq_product_sku UNIQUE (sku)
)
/
CREATE TABLE customer (
    customer_id  NUMBER(10) PRIMARY KEY,
    email        VARCHAR2(200) NOT NULL,                              -- longer than A (120)
    name         VARCHAR2(100) NOT NULL,
    region_id    NUMBER(5) NOT NULL,                                  -- no PHONE column here
    created_at   DATE DEFAULT SYSDATE NOT NULL,
    credit_limit NUMBER(10,2),
    loyalty_tier VARCHAR2(10) DEFAULT 'BASIC' NOT NULL,               -- column only in B
    CONSTRAINT uq_customer_email UNIQUE (email),
    CONSTRAINT fk_customer_region FOREIGN KEY (region_id) REFERENCES region (region_id)
)
/
-- no IX_CUSTOMER_REGION index and no TRG_CUSTOMER_BI trigger here
CREATE TABLE orders (
    order_id    NUMBER(10) PRIMARY KEY,
    customer_id NUMBER(10) NOT NULL,
    order_date  DATE DEFAULT SYSDATE NOT NULL,
    status      VARCHAR2(12) DEFAULT 'NEW' NOT NULL,
    CONSTRAINT ck_orders_status CHECK (status IN ('NEW','PAID','SHIPPED','CANCELLED','RETURNED')),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customer (customer_id)
)
/
CREATE TABLE order_item (
    item_id    NUMBER(10) PRIMARY KEY,
    order_id   NUMBER(10) NOT NULL,
    product_id NUMBER(10) NOT NULL,
    qty        VARCHAR2(6) NOT NULL,                                  -- type differs from A (NUMBER)
    unit_price NUMBER(10,2) NOT NULL,
    CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_item_product FOREIGN KEY (product_id) REFERENCES product (product_id)
)
/
CREATE TABLE audit_note (                                             -- table only in B
    note_id   NUMBER(10) PRIMARY KEY,
    ref_table VARCHAR2(30) NOT NULL,
    note      VARCHAR2(400)
)
/
INSERT INTO region (region_id, name) VALUES (1, 'North')
/
INSERT INTO region (region_id, name) VALUES (2, 'East')
/
INSERT INTO region (region_id, name) VALUES (3, 'West')
/
INSERT INTO product (product_id, sku, name, price) VALUES (1, 'SKU-100', 'Widget', 9.99)
/
INSERT INTO product (product_id, sku, name, price) VALUES (2, 'SKU-200', 'Gadget DELUXE', 24.99)
/
INSERT INTO product (product_id, sku, name, price) VALUES (3, 'SKU-300', 'Gizmo', 5)
/
INSERT INTO customer (customer_id, email, name, region_id, credit_limit) VALUES (1, 'carol@example.com', 'Carol', 2, 750)
/
INSERT INTO customer (customer_id, email, name, region_id, credit_limit) VALUES (2, 'bob@example.com', 'Bob', 3, 500)
/
INSERT INTO customer (customer_id, email, name, region_id, credit_limit) VALUES (3, 'alice@example.com', 'Alice Anderson', 1, 2000)
/
INSERT INTO orders (order_id, customer_id, status) VALUES (1, 1, 'NEW')
/
INSERT INTO orders (order_id, customer_id, status) VALUES (2, 2, 'PAID')
/
INSERT INTO orders (order_id, customer_id, status) VALUES (3, 3, 'RETURNED')
/
INSERT INTO order_item (item_id, order_id, product_id, qty, unit_price) VALUES (1, 1, 1, '1', 9.99)
/
INSERT INTO order_item (item_id, order_id, product_id, qty, unit_price) VALUES (2, 3, 3, '5', 5.00)
/
INSERT INTO audit_note (note_id, ref_table, note) VALUES (1, 'CUSTOMER', 'Imported from legacy system')
/
COMMIT
/
