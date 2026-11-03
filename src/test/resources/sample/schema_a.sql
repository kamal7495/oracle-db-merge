-- Sample schema A (typically the master). Statements are separated by a line containing only "/".
-- FK chain: REGION <- CUSTOMER <- ORDERS <- ORDER_ITEM -> PRODUCT
CREATE SEQUENCE seq_customer START WITH 100 INCREMENT BY 1 NOCACHE
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
    price      NUMBER(10,2) DEFAULT 0 NOT NULL,
    CONSTRAINT uq_product_sku UNIQUE (sku)
)
/
CREATE TABLE customer (
    customer_id  NUMBER(10) PRIMARY KEY,
    email        VARCHAR2(120) NOT NULL,
    name         VARCHAR2(100) NOT NULL,
    phone        VARCHAR2(20),
    region_id    NUMBER(5) NOT NULL,
    created_at   DATE DEFAULT SYSDATE NOT NULL,
    credit_limit NUMBER(10,2),
    CONSTRAINT uq_customer_email UNIQUE (email),
    CONSTRAINT fk_customer_region FOREIGN KEY (region_id) REFERENCES region (region_id)
)
/
CREATE INDEX ix_customer_region ON customer (region_id)
/
CREATE TABLE orders (
    order_id    NUMBER(10) PRIMARY KEY,
    customer_id NUMBER(10) NOT NULL,
    order_date  DATE DEFAULT SYSDATE NOT NULL,
    status      VARCHAR2(12) DEFAULT 'NEW' NOT NULL,
    CONSTRAINT ck_orders_status CHECK (status IN ('NEW','PAID','SHIPPED','CANCELLED')),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customer (customer_id)
)
/
CREATE TABLE order_item (
    item_id    NUMBER(10) PRIMARY KEY,
    order_id   NUMBER(10) NOT NULL,
    product_id NUMBER(10) NOT NULL,
    qty        NUMBER(6) NOT NULL,
    unit_price NUMBER(10,2) NOT NULL,
    CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_item_product FOREIGN KEY (product_id) REFERENCES product (product_id)
)
/
CREATE OR REPLACE TRIGGER trg_customer_bi BEFORE INSERT ON customer FOR EACH ROW WHEN (NEW.customer_id IS NULL)
BEGIN
    :NEW.customer_id := seq_customer.NEXTVAL;
END;
/
INSERT INTO region (region_id, name) VALUES (1, 'North')
/
INSERT INTO region (region_id, name) VALUES (2, 'South')
/
INSERT INTO product (product_id, sku, name, price) VALUES (1, 'SKU-100', 'Widget', 9.99)
/
INSERT INTO product (product_id, sku, name, price) VALUES (2, 'SKU-200', 'Gadget', 19.99)
/
INSERT INTO customer (customer_id, email, name, phone, region_id, credit_limit) VALUES (1, 'alice@example.com', 'Alice A', '555-0001', 1, 1000)
/
INSERT INTO customer (customer_id, email, name, phone, region_id, credit_limit) VALUES (2, 'bob@example.com', 'Bob', '555-0002', 2, 500)
/
INSERT INTO orders (order_id, customer_id, status) VALUES (1, 1, 'PAID')
/
INSERT INTO orders (order_id, customer_id, status) VALUES (2, 2, 'NEW')
/
INSERT INTO order_item (item_id, order_id, product_id, qty, unit_price) VALUES (1, 1, 1, 2, 9.99)
/
INSERT INTO order_item (item_id, order_id, product_id, qty, unit_price) VALUES (2, 2, 2, 1, 19.99)
/
COMMIT
/
