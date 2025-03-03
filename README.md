# hotShop

电商秒杀平台 hotShop

## `API接口文档`

`https://apifox.com/apidoc/shared-00464c6c-6131-409a-8dc2-6f63cee93024`

## `CRUD`
- User
- Product
- Order

## `技术架构`

springboot mysql  mybatis

嵌入springSecurity JWT

### 模块化
```mermaid
graph TD
A(admin) --> B(portal)
B --> C(domain)
C --> D(security)
D --> E(common)
```
### 采取双令牌机制，自动刷新令牌
```mermaid
graph TD
A[客户端] -->|1. 提交凭证| B(登录接口)
B -->|2. 生成双令牌| C[Access Token]
B -->|2. 生成双令牌| D[Refresh Token]
C -->|短期有效 1小时| E[API访问]
D -->|长期有效 30天| F[令牌刷新]
E -->|401过期| F
F -->|获取新令牌| C
```
### 安全认证
```mermaid
sequenceDiagram
participant Client
participant AuthServer
participant ResourceServer

    Client->>AuthServer: 1. 登录请求
    AuthServer-->>Client: 返回双令牌
    Client->>ResourceServer: 2. 携带Access Token请求资源
    ResourceServer-->>Client: 返回401（Token过期）
    Client->>AuthServer: 3. 使用Refresh Token请求新Token
    AuthServer-->>Client: 返回新Access Token
    Client->>ResourceServer: 4. 使用新Token重试请求
    ResourceServer-->>Client: 返回请求结果
```

## `业务架构`
