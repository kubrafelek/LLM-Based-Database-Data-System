-- Demo Database Creation Script for LLM Data Discovery Case Study
-- This script creates sample tables with various data types for testing classification

-- Create database (run this separately if needed)
-- CREATE DATABASE llm_discovery_demo;

-- Connect to the database
-- \c llm_discovery_demo;

-- Enable extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Drop tables if they exist (for clean setup)
DROP TABLE IF EXISTS order_items CASCADE;
DROP TABLE IF EXISTS orders CASCADE;
DROP TABLE IF EXISTS products CASCADE;
DROP TABLE IF EXISTS customers CASCADE;
DROP TABLE IF EXISTS employees CASCADE;
DROP TABLE IF EXISTS customer_addresses CASCADE;
DROP TABLE IF EXISTS payment_methods CASCADE;
DROP TABLE IF EXISTS audit_logs CASCADE;

-- 1. CUSTOMERS TABLE (PII Data)
CREATE TABLE customers (
  customer_id SERIAL PRIMARY KEY,
  customer_uuid UUID DEFAULT uuid_generate_v4(),
  first_name VARCHAR(50) NOT NULL,
  last_name VARCHAR(50) NOT NULL,
  email VARCHAR(100) UNIQUE NOT NULL,
  phone_number VARCHAR(20),
  date_of_birth DATE,
  national_id VARCHAR(11), -- Turkish TC Kimlik No format
  gender CHAR(1) CHECK (gender IN ('M', 'F', 'O')),
  registration_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  last_login TIMESTAMP,
  is_active BOOLEAN DEFAULT TRUE,
  customer_notes TEXT,
  preferred_language VARCHAR(5) DEFAULT 'tr-TR'
);

-- 2. CUSTOMER ADDRESSES (Geographic Data)
CREATE TABLE customer_addresses (
  address_id SERIAL PRIMARY KEY,
  customer_id INTEGER REFERENCES customers(customer_id),
  address_type VARCHAR(20) CHECK (address_type IN ('home', 'work', 'billing', 'shipping')),
  street_address TEXT NOT NULL,
  district VARCHAR(50),
  city VARCHAR(50) NOT NULL,
  postal_code VARCHAR(10),
  country VARCHAR(50) DEFAULT 'Turkey',
  latitude DECIMAL(10, 8),
  longitude DECIMAL(11, 8),
  is_default BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3. EMPLOYEES (PII + Financial Data)
CREATE TABLE employees (
  employee_id SERIAL PRIMARY KEY,
  employee_code VARCHAR(10) UNIQUE NOT NULL,
  first_name VARCHAR(50) NOT NULL,
  last_name VARCHAR(50) NOT NULL,
  email VARCHAR(100) UNIQUE NOT NULL,
  phone VARCHAR(20),
  hire_date DATE NOT NULL,
  department VARCHAR(50),
  position VARCHAR(100),
  salary DECIMAL(10, 2),
  bonus_percentage DECIMAL(5, 2),
  bank_account_iban VARCHAR(34), -- Turkish IBAN format
  tax_number VARCHAR(10),
  social_security_number VARCHAR(11),
  manager_id INTEGER REFERENCES employees(employee_id),
  status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'inactive', 'terminated')),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 4. PRODUCTS (Categorical + Numerical Data)
CREATE TABLE products (
  product_id SERIAL PRIMARY KEY,
  product_code VARCHAR(20) UNIQUE NOT NULL,
  product_name VARCHAR(200) NOT NULL,
  category VARCHAR(50),
  subcategory VARCHAR(50),
  brand VARCHAR(100),
  description TEXT,
  unit_price DECIMAL(10, 2) NOT NULL,
  cost_price DECIMAL(10, 2),
  weight_kg DECIMAL(8, 3),
  dimensions_cm VARCHAR(50), -- "LxWxH" format
  color VARCHAR(30),
  size VARCHAR(10),
  stock_quantity INTEGER DEFAULT 0,
  minimum_stock_level INTEGER DEFAULT 5,
  is_active BOOLEAN DEFAULT TRUE,
  created_date DATE DEFAULT CURRENT_DATE,
  last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  rating DECIMAL(3, 2) CHECK (rating >= 0 AND rating <= 5),
  review_count INTEGER DEFAULT 0
);

-- 5. PAYMENT METHODS (Financial Data)
CREATE TABLE payment_methods (
  payment_method_id SERIAL PRIMARY KEY,
  customer_id INTEGER REFERENCES customers(customer_id),
  payment_type VARCHAR(20) CHECK (payment_type IN ('credit_card', 'debit_card', 'bank_transfer', 'digital_wallet')),
  card_number_masked VARCHAR(19), -- Masked format: ****-****-****-1234
  card_holder_name VARCHAR(100),
  expiry_month INTEGER CHECK (expiry_month >= 1 AND expiry_month <= 12),
  expiry_year INTEGER,
  bank_name VARCHAR(100),
  is_default BOOLEAN DEFAULT FALSE,
  is_active BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 6. ORDERS (Temporal + Numerical Data)
CREATE TABLE orders (
  order_id SERIAL PRIMARY KEY,
  order_number VARCHAR(20) UNIQUE NOT NULL,
  customer_id INTEGER REFERENCES customers(customer_id),
  employee_id INTEGER REFERENCES employees(employee_id),
  order_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  delivery_date DATE,
  order_status VARCHAR(20) DEFAULT 'pending' CHECK (order_status IN ('pending', 'confirmed', 'processing', 'shipped', 'delivered', 'cancelled')),
  payment_method_id INTEGER REFERENCES payment_methods(payment_method_id),
  subtotal DECIMAL(10, 2) NOT NULL,
  tax_amount DECIMAL(10, 2) DEFAULT 0,
  shipping_cost DECIMAL(10, 2) DEFAULT 0,
  discount_amount DECIMAL(10, 2) DEFAULT 0,
  total_amount DECIMAL(10, 2) NOT NULL,
  currency_code VARCHAR(3) DEFAULT 'TRY',
  shipping_address_id INTEGER REFERENCES customer_addresses(address_id),
  billing_address_id INTEGER REFERENCES customer_addresses(address_id),
  tracking_number VARCHAR(50),
  notes TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 7. ORDER ITEMS (Numerical Data)
CREATE TABLE order_items (
  order_item_id SERIAL PRIMARY KEY,
  order_id INTEGER REFERENCES orders(order_id),
  product_id INTEGER REFERENCES products(product_id),
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  unit_price DECIMAL(10, 2) NOT NULL,
  discount_percentage DECIMAL(5, 2) DEFAULT 0,
  line_total DECIMAL(10, 2) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 8. AUDIT LOGS (Temporal + Textual Data)
CREATE TABLE audit_logs (
  log_id SERIAL PRIMARY KEY,
  table_name VARCHAR(50) NOT NULL,
  record_id INTEGER NOT NULL,
  action_type VARCHAR(10) CHECK (action_type IN ('INSERT', 'UPDATE', 'DELETE')),
  old_values JSONB,
  new_values JSONB,
  changed_by INTEGER REFERENCES employees(employee_id),
  ip_address INET,
  user_agent TEXT,
  session_id VARCHAR(100),
  timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  description TEXT
);

-- INSERT SAMPLE DATA

-- Sample Customers (PII Data)
INSERT INTO customers (first_name, last_name, email, phone_number, date_of_birth, national_id, gender, customer_notes) VALUES
('Ahmet', 'Yılmaz', 'ahmet.yilmaz@email.com', '+90 532 123 4567', '1985-03-15', '12345678901', 'M', 'VIP müşteri, özel indirim uygulanır'),
('Fatma', 'Kaya', 'fatma.kaya@gmail.com', '+90 533 987 6543', '1990-07-22', '98765432109', 'F', 'Düzenli alışveriş yapan müşteri'),
('Mehmet', 'Demir', 'mehmet.demir@hotmail.com', '+90 534 555 1234', '1978-12-03', '11223344556', 'M', NULL),
('Ayşe', 'Şahin', 'ayse.sahin@yahoo.com', '+90 535 777 8888', '1992-05-18', '66778899001', 'F', 'Şikayet geçmişi var, dikkatli davranılmalı'),
('Can', 'Özkan', 'can.ozkan@outlook.com', '+90 536 111 2222', '1988-09-10', '55443322110', 'M', 'Kurumsal müşteri temsilcisi');

-- Sample Customer Addresses (Geographic Data)
INSERT INTO customer_addresses (customer_id, address_type, street_address, district, city, postal_code, latitude, longitude, is_default) VALUES
(1, 'home', 'Atatürk Caddesi No:123 Daire:5', 'Kadıköy', 'İstanbul', '34710', 40.9808, 29.0312, TRUE),
(1, 'work', 'Levent Mahallesi Büyükdere Cad. No:45', 'Şişli', 'İstanbul', '34394', 41.0814, 29.0092, FALSE),
(2, 'home', 'Kızılay Meydanı No:67', 'Çankaya', 'Ankara', '06420', 39.9208, 32.8541, TRUE),
(3, 'home', 'Kordon Boyu No:89', 'Konak', 'İzmir', '35250', 38.4189, 27.1287, TRUE),
(4, 'home', 'Cumhuriyet Caddesi No:234', 'Osmangazi', 'Bursa', '16040', 40.1885, 29.0610, TRUE),
(5, 'work', 'Teknokent Binası Kat:3', 'Çankaya', 'Ankara', '06800', 39.8667, 32.7500, TRUE);

-- Sample Employees (PII + Financial Data)
INSERT INTO employees (employee_code, first_name, last_name, email, phone, hire_date, department, position, salary, bonus_percentage, bank_account_iban, tax_number, social_security_number) VALUES
('EMP001', 'Ali', 'Veli', 'ali.veli@company.com', '+90 532 100 2000', '2020-01-15', 'IT', 'Senior Developer', 15000.00, 10.00, 'TR33 0006 1005 1978 6457 8413 26', '1234567890', '12345678901'),
('EMP002', 'Zeynep', 'Arslan', 'zeynep.arslan@company.com', '+90 533 200 3000', '2019-03-20', 'Sales', 'Sales Manager', 12000.00, 15.00, 'TR64 0001 2009 4500 0058 0012 35', '2345678901', '23456789012'),
('EMP003', 'Burak', 'Çelik', 'burak.celik@company.com', '+90 534 300 4000', '2021-06-10', 'HR', 'HR Specialist', 8000.00, 5.00, 'TR56 0004 6007 1234 5678 9012 34', '3456789012', '34567890123'),
('EMP004', 'Selin', 'Aydın', 'selin.aydin@company.com', '+90 535 400 5000', '2018-11-05', 'Finance', 'Financial Analyst', 10000.00, 8.00, 'TR12 0010 3000 0000 0012 3456 78', '4567890123', '45678901234'),
('EMP005', 'Emre', 'Koç', 'emre.koc@company.com', '+90 536 500 6000', '2022-02-28', 'IT', 'DevOps Engineer', 13000.00, 12.00, 'TR98 0006 2000 1270 0006 2956 89', '5678901234', '56789012345');

-- Sample Products (Categorical + Numerical Data)
INSERT INTO products (product_code, product_name, category, subcategory, brand, description, unit_price, cost_price, weight_kg, dimensions_cm, color, size, stock_quantity, rating, review_count) VALUES
('LAPTOP001', 'MacBook Pro 13"', 'Electronics', 'Laptops', 'Apple', 'M2 çipli MacBook Pro, 256GB SSD', 25999.00, 20000.00, 1.4, '30.4x21.2x1.56', 'Space Gray', '13"', 15, 4.5, 128),
('PHONE001', 'iPhone 14', 'Electronics', 'Smartphones', 'Apple', '128GB iPhone 14, Dual Camera', 18999.00, 15000.00, 0.172, '14.67x7.15x0.78', 'Blue', '128GB', 25, 4.3, 89),
('SHIRT001', 'Polo T-Shirt', 'Clothing', 'Shirts', 'Lacoste', '100% Pamuk Polo T-Shirt', 299.00, 150.00, 0.2, '70x50x2', 'Navy Blue', 'L', 50, 4.1, 45),
('BOOK001', 'Clean Code', 'Books', 'Programming', 'Pearson', 'Robert C. Martin - Temiz Kod Yazma Sanatı', 89.90, 45.00, 0.5, '23x15x3', 'White', 'Paperback', 100, 4.8, 234),
('CHAIR001', 'Ergonomic Office Chair', 'Furniture', 'Office', 'Herman Miller', 'Ayarlanabilir ergonomik ofis koltuğu', 2499.00, 1500.00, 25.0, '68x68x120', 'Black', 'Standard', 8, 4.6, 67),
('COFFEE001', 'Arabica Coffee Beans', 'Food', 'Beverages', 'Starbucks', 'Premium Arabica kahve çekirdekleri 1kg', 149.90, 80.00, 1.0, '20x15x8', 'Brown', '1kg', 200, 4.2, 156),
('WATCH001', 'Apple Watch Series 8', 'Electronics', 'Wearables', 'Apple', 'GPS + Cellular, 45mm', 8999.00, 6500.00, 0.052, '4.5x3.8x1.07', 'Midnight', '45mm', 12, 4.4, 78);

-- Sample Payment Methods (Financial Data)
INSERT INTO payment_methods (customer_id, payment_type, card_number_masked, card_holder_name, expiry_month, expiry_year, bank_name, is_default) VALUES
(1, 'credit_card', '****-****-****-1234', 'AHMET YILMAZ', 12, 2025, 'Garanti BBVA', TRUE),
(1, 'debit_card', '****-****-****-5678', 'AHMET YILMAZ', 8, 2024, 'İş Bankası', FALSE),
(2, 'credit_card', '****-****-****-9012', 'FATMA KAYA', 6, 2026, 'Yapı Kredi', TRUE),
(3, 'credit_card', '****-****-****-3456', 'MEHMET DEMIR', 3, 2025, 'Akbank', TRUE),
(4, 'digital_wallet', NULL, 'AYŞE ŞAHIN', NULL, NULL, 'PayPal', TRUE),
(5, 'bank_transfer', NULL, 'CAN ÖZKAN', NULL, NULL, 'Ziraat Bankası', TRUE);

-- Sample Orders (Temporal + Numerical Data)
INSERT INTO orders (order_number, customer_id, employee_id, order_date, delivery_date, order_status, payment_method_id, subtotal, tax_amount, shipping_cost, discount_amount, total_amount, shipping_address_id, billing_address_id, tracking_number, notes) VALUES
('ORD-2024-001', 1, 2, '2024-01-15 10:30:00', '2024-01-18', 'delivered', 1, 26288.90, 4732.00, 29.90, 500.00, 30550.80, 1, 1, 'TRK123456789', 'Hızlı teslimat talep edildi'),
('ORD-2024-002', 2, 2, '2024-01-20 14:45:00', '2024-01-23', 'delivered', 3, 299.00, 53.82, 15.00, 0.00, 367.82, 3, 3, 'TRK987654321', NULL),
('ORD-2024-003', 3, 1, '2024-02-05 09:15:00', '2024-02-08', 'shipped', 4, 2648.90, 476.80, 39.90, 100.00, 3065.60, 4, 4, 'TRK456789123', 'Kurumsal fatura kesildi'),
('ORD-2024-004', 1, 3, '2024-02-10 16:20:00', NULL, 'processing', 2, 18999.00, 3419.82, 0.00, 1000.00, 21418.82, 2, 1, NULL, 'VIP müşteri indirimi uygulandı'),
('ORD-2024-005', 4, 2, '2024-02-15 11:00:00', '2024-02-18', 'delivered', 5, 239.80, 43.16, 25.00, 50.00, 257.96, 5, 5, 'TRK789123456', 'Hediye paketi yapıldı');

-- Sample Order Items (Numerical Data)
INSERT INTO order_items (order_id, product_id, quantity, unit_price, discount_percentage, line_total) VALUES
(1, 1, 1, 25999.00, 0.00, 25999.00),
(1, 7, 1, 8999.00, 10.00, 8099.10),
(1, 6, 2, 149.90, 0.00, 299.80),
(2, 3, 1, 299.00, 0.00, 299.00),
(3, 5, 1, 2499.00, 0.00, 2499.00),
(3, 4, 1, 89.90, 0.00, 89.90),
(3, 6, 1, 149.90, 10.00, 134.91),
(4, 2, 1, 18999.00, 0.00, 18999.00),
(5, 6, 1, 149.90, 0.00, 149.90),
(5, 4, 1, 89.90, 0.00, 89.90);

-- Sample Audit Logs (Temporal + Textual Data)
INSERT INTO audit_logs (table_name, record_id, action_type, old_values, new_values, changed_by, ip_address, user_agent, session_id, description) VALUES
('customers', 1, 'INSERT', NULL, '{"first_name": "Ahmet", "last_name": "Yılmaz", "email": "ahmet.yilmaz@email.com"}', 1, '192.168.1.100', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36', 'sess_abc123', 'Yeni müşteri kaydı oluşturuldu'),
('orders', 1, 'UPDATE', '{"order_status": "processing"}', '{"order_status": "shipped"}', 2, '10.0.0.50', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)', 'sess_def456', 'Sipariş durumu güncellendi'),
('products', 1, 'UPDATE', '{"stock_quantity": 20}', '{"stock_quantity": 15}', 1, '172.16.0.10', 'Mozilla/5.0 (X11; Linux x86_64)', 'sess_ghi789', 'Stok miktarı satış sonrası güncellendi'),
('employees', 3, 'UPDATE', '{"salary": 7500.00}', '{"salary": 8000.00}', 4, '192.168.1.200', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)', 'sess_jkl012', 'Maaş zammı uygulandı'),
('payment_methods', 2, 'INSERT', NULL, '{"customer_id": 1, "payment_type": "debit_card"}', 2, '10.0.0.75', 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_0)', 'sess_mno345', 'Yeni ödeme yöntemi eklendi');

-- Create indexes for better performance
CREATE INDEX idx_customers_email ON customers(email);
CREATE INDEX idx_customers_phone ON customers(phone_number);
CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_orders_date ON orders(order_date);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);
CREATE INDEX idx_order_items_product_id ON order_items(product_id);
CREATE INDEX idx_audit_logs_timestamp ON audit_logs(timestamp);
CREATE INDEX idx_audit_logs_table_record ON audit_logs(table_name, record_id);

-- Create views for complex queries (optional)
CREATE VIEW customer_order_summary AS
SELECT 
  c.customer_id,
  c.first_name,
  c.last_name,
  c.email,
  COUNT(o.order_id) as total_orders,
  SUM(o.total_amount) as total_spent,
  MAX(o.order_date) as last_order_date
FROM customers c
LEFT JOIN orders o ON c.customer_id = o.customer_id
GROUP BY c.customer_id, c.first_name, c.last_name, c.email;

-- Grant permissions (adjust as needed)
-- GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO your_app_user;
-- GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO your_app_user;

-- Display table information
SELECT 
  schemaname,
  tablename,
  tableowner
FROM pg_tables 
WHERE schemaname = 'public'
ORDER BY tablename;

-- Display sample record counts
SELECT 'customers' as table_name, COUNT(*) as record_count FROM customers
UNION ALL
SELECT 'customer_addresses', COUNT(*) FROM customer_addresses
UNION ALL
SELECT 'employees', COUNT(*) FROM employees
UNION ALL
SELECT 'products', COUNT(*) FROM products
UNION ALL
SELECT 'payment_methods', COUNT(*) FROM payment_methods
UNION ALL
SELECT 'orders', COUNT(*) FROM orders
UNION ALL
SELECT 'order_items', COUNT(*) FROM order_items
UNION ALL
SELECT 'audit_logs', COUNT(*) FROM audit_logs;