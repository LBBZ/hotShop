# 快速跳转
<details>
<summary>📦 hotShop 文档</summary>

- <a href="#1-api接口文档">📘 1. API接口文档</a>
- <a href="#2-crud">📚 2. CRUD模块</a>
- <a href="#3-技术架构">🛠️ 3. 技术架构</a>
     - <a href="#31-模块化">🔩 3.1 模块化</a>
     - <a href="#32-双令牌机制">🔐 3.2 双令牌机制</a>
     - <a href="#33-安全认证">🔒 3.3 安全认证</a>
     - <a href="#34-Redis-分发机制">📤 3.4 Redis 分发机制</a>
- <a href="#4-业务架构">🤹 4. 业务架构</a>
</details>

<details>
<summary>🐋 部署docker</summary>

- <a href="#1-docker安装及配置">📦 1. Docker安装配置</a>
     - <a href="#11-环境准备">🖥️ 1.1 环境准备</a>
     - <a href="#12-安装-docker">🔧 1.2 安装 Docker</a>
     - <a href="#13-安装-docker-compose">📝 1.3 安装 Docker Compose</a>
     - <a href="#14-安装maven和jdk">⚙️ 1.4 安装maven和JDK</a>
- <a href="#2-构建和运行-docker-服务">🌊 2. 服务构建运行</a>
     - <a href="#21-传输项目文件">📤 2.1 传输项目文件</a>
     - <a href="#22-快速启动">🚀 2.2 快速启动</a>
     - <a href="#23-脚本启动">📝 2.3 脚本启动</a>
     - <a href="#24-自行启动">🛠️ 2.4 自行启动</a>
- <a href="#3-验证部署">🛳️ 3. 部署验证</a>
- <a href="#4-维护和更新">⚓ 4. 维护更新</a>
</details>

# hotShop 文档

电商秒杀平台 hotShop

## 1. API接口文档

`https://apifox.com/apidoc/shared-00464c6c-6131-409a-8dc2-6f63cee93024`

## 2. CRUD
- User
- Product
- Order

## 3. 技术架构

|      技术栈       | 说明                   | 详细                                                   | 状态  |
|:--------------:|:---------------------|:-----------------------------------------------------|:---:|
|   SpringBoot   | 用于快速开发 Spring 应用的框架  | 略                                                    | 已集成 |
|     Lombok     | 减少 boilerplate 代码的工具 | 略                                                    | 已集成 |
|     MySql      | 关系型数据库,用于数据存储        | 存储信息表,用户、订单、商品等                                      | 已集成 |
|    MyBatis     | 持久层框架,简化数据库操作        | 略                                                    | 已集成 |
|    Swagger     | 用于生成和展示 API 文档的工具    | 略                                                    | 已集成 |
| SpringSecurity | 安全框架,用于实现认证和授权       | 管理端、用户端的安全认证,拦截请求等操作                                 | 已集成 |
|      JWT       | 用于生成和验证 JWT 令牌       | 生成定时过期的认证令牌(访问令牌、刷新令牌),服务器无需储存令牌即可验证                 | 已集成 |
|     Redis      | 高性能的内存数据库            | 设置令牌黑名单,登出的账号的令牌会存储在redis黑名单中<br/>消息监听器分布式锁,防止消息重复消费 | 已集成 |
|    RabbitMQ    | 消息队列,处理转发消息          | 延迟消息队列,处理过期订单,减少数据库扫描频率                              | 已集成 |
### 3.1 模块化
```mermaid
graph TD
A(admin\n负责管理员前台模块) --> X
B(portal\n负责用户前台模块) --> X
C(task\n负责后台任务\n如定时任务) --> Y
X --> Y
Y --> Z1
Y --> Z2
X(security\n安全认证\n请求拦截\nJWT生成)
Y(domain\n领域模块\n核心功能)
Z1(common\n通用组件)
Z2(infrastructure\n基础设施\n中间价)
```
### 3.2 双令牌机制
    使用 JWT 无需将令牌存至数据库,减少数据库压力
    双令牌减少令牌存活时间,用户下线自动拉黑存活令牌
    使用redis来储存黑名单,减少拦截验证令牌的数据库开销,redis根据令牌ttl自动删除过期令牌
```mermaid
graph TD
A[客户端] -->|提交凭证| B(登录接口)
B -->|生成双令牌| C[Access Token]
B -->|生成双令牌| D[Refresh Token]
C -->|短期有效 1小时| E[API访问]
D -->|长期有效 30天| F[令牌刷新]
E -->|401过期| F
F -->|获取新令牌| C
```
### 3.3 安全认证
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
### 3.4 Redis 分发机制
    通过分发 RedisTemplate 可以减少初始化链接的数量,同时可以灵活的根据需求自动化创建数据库的链接
```mermaid
graph TD
    A[应用调用RedisService] --> B[RedisService根据dbIndex获取RedisTemplate]
    B --> C{RedisTemplateGenerator检查缓存}
    C -->|缓存存在| D[直接返回缓存中的RedisTemplate]
    C -->|缓存不存在| E[创建新的LettuceConnectionFactory]
    E --> F[使用工厂创建并配置RedisTemplate]
    F --> G[将RedisTemplate存入缓存]
    G --> D
    D --> H[使用RedisTemplate操作Redis数据库]
```

### 3.5 超时订单自动取消
    订单新建时向 RabbitMQ 延迟交换机发送订单消息,延迟交换机在订单超时后向发送到相关队列,监听器监听并处理订单消息
    并用定时任务扫描数据库做兜底处理
```mermaid
graph TD
    A1[新建订单\n发送消息] --> A2[延迟交换机延迟过后\n转发消息到消息队列] --> B{获取分布式锁}
    B -->|成功| C[查询订单信息]
    C --> D{订单状态是否为待处理}
    D -->|是| E[取消订单]
    D -->|否| F[结束]
    B -->|失败| F
    E --> G[释放分布式锁]
    G --> F
```    
## 4. 业务架构

# 部署docker

## 1. Docker 安装及配置
### 1.1 环境准备
    - 确保虚拟机或服务器满足以下要求：
        - 操作系统：龙蜥8.9 (Anolis8.9)
        - 内存：至少 2GB
        - 磁盘空间：至少 50GB

### 1.2 安装 Docker
   - 更新系统包
     ```bash
     sudo yum update -y
     ```
   - 确保删除原有的依赖包
     ```bash
     sudo yum remove docker \
     docker-client \
     docker-client-latest \
     docker-common \
     docker-latest \
     docker-latest-logrotate \
     docker-logrotate \
     docker-engine
     ```
   - 安装底层工具
     ```bash
     yum install -y yum-utils device-mapper-persistent-data lvm2
     ```  
   - 添加 Docker 的镜像
     ```bash
     自行添加
     ```
   - 安装 Docker
     ```bash
     yum -y install docker-ce docker-ce-cli containerd.io --allowerasing
     ```
   - 验证 Docker 安装
     ```bash
     docker --version
     ```
     应显示 Docker 的版本信息，例如 :
     ```
     Docker version 26.1.3, build b72abbb
     ```
   - 启动 Docker 服务
     ```bash
     sudo systemctl start docker
     ```
   - 设置 Docker 开机自启
     ```bash
     sudo systemctl enable docker
     ```
### 1.3 安装 Docker Compose
   - 下载docker-compose文件
     ```bash
     sudo curl -L https://github.com/docker/compose/releases/download/v2.21.0/docker-compose-`uname -s`-`uname -m` -o /usr/local/bin/docker-compose
     ```
   - 添加执行权限
     ```bash
     sudo chmod +x /usr/local/bin/docker-compose
     ```
   - 验证安装
     ```bash
     docker-compose --version
     ```
     应显示 docker-compose 的版本信息,例如 :
     ```bash
     Docker Compose version v2.21.0
     ```
### 1.4 安装maven和JDK
   - 安装并配置 OpenJDK 17
      ```bash
      sudo yum install java-17-openjdk java-17-openjdk-devel
      sudo alternatives --config java
      sudo nano ~/.bashrc
      export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
      export PATH=$JAVA_HOME/bin:$PATH
      source ~/.bashrc
      java -version
      ```
   - 安装并配置 maven 3.99
      ```bash
      wget https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz
      tar -zxvf apache-maven-3.9.9-bin.tar.gz
      export MAVEN_HOME=/path/to/apache-maven-3.6.3
      export PATH=$MAVEN_HOME/bin:$PATH
      mvn -v
      ```
## 2 构建和运行 Docker 服务

### 2.1 传输项目文件
   - 将本地项目文件传输到虚拟机 :
      
     项目文件在linux下请跳过这步
     ```bash
     scp -r /path/hotShop 用户名@虚拟机IP地址:/opt
     ```

### 2.2 快速启动
   - 在文件目录下运行script中的run.sh :
     ```bash
     # 授予运行权限
     chmod +x run.sh
     ./run.sh
     ```
### 2.3 脚本启动
   - 在当前目录下运行script中的deploy.sh :
     ```bash
     # 授予运行权限
     chmod +x deploy.sh
     ./deploy.sh -h
     ```
|     功能      | 命令                                 | 说明              |
|:-----------:|:-----------------------------------|:----------------|
|    查看帮助     | `./deploy.sh -h`                   | 查看脚本使用帮助        | 
|    构建项目     | `./deploy.sh -a bulid`             | 构建项目,创建jar包     | 
|   启动开发环境    | `./deploy.sh -a start`             | 默认启动所有服务        | 
| 查看admin服务日志 | `./deploy.sh -a logs -s admin`     | 实时查看指定服务日志      |
| 重建portal服务  | `./deploy.sh -a rebuild -s portal` | 强制重建单个服务        |
|   清理所有资源    | `./deploy.sh -a clean`             | 清理Docker资源和构建文件 |
### 2.4 自行启动
   - 构建并启动服务
     ```bash
     # 进入项目根目录
     cd /opt/hotshop
      
     # 清理并打包所有模块
     mvn clean package -DskipTests
      
     # 确认 portal、admin模块的 JAR 文件存在
     ls portal/target/*.jar
     ls admin/target/*.jar
     ```
     应输出以下内容 :
     ```bash
     portal/target/portal-0.0.1-SNAPSHOT.jar
     admin/target/admin-0.0.1-SNAPSHOT.jar
     ```
   - 构建镜像并启动
     ```bash
     #如果有残留请执行以下命令
     #docker-compose down
     #docker system prune -a --volumes
      
     docker-compose build --no-cache
      
     docker-compose up -d
     ```
   - 查看服务状态
     ```bash
     docker-compose ps
     ```
     应输出以下内容 :
     ```bash
     NAME             IMAGE                    COMMAND                               SERVICE          CREATED         STATUS                   PORTS
     hotShop-admin    hotshop-admin-service    "java -jar /app.jar"                  admin-service    9 seconds ago   Up 2 seconds             0.0.0.0:8088->8088/tcp, :::8088->8088/tcp
     hotShop-mysql    mysql:8.0                "docker-entrypoint.sh mysqld"         mysql            9 seconds ago   Up 8 seconds (healthy)   33060/tcp, 0.0.0.0:4306->3306/tcp, :::4306->3306/tcp
     hotShop-portal   hotshop-portal-service   "java -jar /app.jar"                  portal-service   9 seconds ago   Up 2 seconds             0.0.0.0:8080->8080/tcp, :::8080->8080/tcp
     hotShop-redis    redis:alpine             "docker-entrypoint.sh redis-server"   redis            9 seconds ago   Up 8 seconds (healthy)   0.0.0.0:7379->6379/tcp, :::7379->6379/tcp
     ```

## 3 验证部署
   - 访问应用的前端界面或 API,确保其正常工作。
   - {虚拟机ip}:8080/swagger-ui/index.html
   - {虚拟机ip}:8088/swagger-ui/index.html

## 4 维护和更新
   - 开始服务、停止服务、查看服务实时日志
     ```bash
     docker-compose start
     docker-compose stop
     docker-compose logs -f admin-service #or portal-service
     ```
   - 初始化或重新部署服务
     ```bash
     docker-compose up -d
     #or
     docker-compose up #将打印控制台信息
     ```
   - 删除服务
     ```bash
     docker-compose down -v
     ```
   