# shield-ratelimiter
> 基于Redis的分布式限流工具包

在分布式领域，我们难免会遇到并发量突增，对后端服务造成高压力，严重甚至会导致系统宕机。为避免这种问题，我们通常会为接口添加限流、降级、熔断等能力，从而使接口更为健壮。Java领域常见的开源组件有Netflix的hystrix，阿里系开源的sentinel等，都是蛮不错的限流熔断框架。

今天我们就基于Redis组件的特性，实现一个分布式限流组件，名字就定为shield-ratelimiter。
<!--more-->
## 原理
首先解释下为何采用Redis作为限流组件的核心。

通俗地讲，假设一个用户（用IP判断）每秒访问某服务接口的次数不能超过10次，那么我们可以在Redis中创建一个键，并设置键的过期时间为60秒。

当一个用户对此服务接口发起一次访问就把键值加1，在单位时间（此处为1s）内当键值增加到10的时候，就禁止访问服务接口。PS:在某种场景中添加访问时间间隔还是很有必要的。我们本次不考虑间隔时间，只关注单位时间内的访问次数。

## 需求
原理已经讲过了，说下需求。
1. 基于Redis的incr及过期机制开发
2. 调用方便，声明式
3. Spring支持


基于上述需求，我们决定基于注解方式进行核心功能开发，基于Spring-boot-starter作为基础环境，从而能够很好的适配Spring环境。

另外，在本次开发中，我们不通过简单的调用Redis的java类库API实现对Redis的incr操作。

原因在于，我们要保证整个限流的操作是原子性的，如果用Java代码去做操作及判断，会有并发问题。这里我决定采用Lua脚本进行核心逻辑的定义。
## 为何使用Lua
在正式开发前，我简单介绍下对Redis的操作中，为何推荐使用Lua脚本。

1. 减少网络开销: 不使用 Lua 的代码需要向 Redis 发送多次请求, 而脚本只需一次即可, 减少网络传输;
2. 原子操作: Redis 将整个脚本作为一个原子执行, 无需担心并发, 也就无需事务;
3. 复用: 脚本会永久保存 Redis 中, 其他客户端可继续使用.

Redis添加了对Lua的支持，能够很好的满足原子性、事务性的支持，让我们免去了很多的异常逻辑处理。对于Lua的语法不是本文的主要内容，感兴趣的可以自行查找资料。
## 正式开发
到这里，我们正式开始手写限流组件的进程。
### 1. 工程定义
项目基于maven构建，主要依赖Spring-boot-starter，我们主要在springboot上进行开发，因此自定义的开发包可以直接依赖下面这个坐标，方便进行包管理。版本号自行选择稳定版。

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
            <version>1.4.2.RELEASE</version>
        </dependency>
### 2. Redis整合
由于我们是基于Redis进行的限流操作，因此需要整合Redis的类库，上面已经讲到，我们是基于Springboot进行的开发，因此这里可以直接整合RedisTemplate。
#### 2.1 坐标引入
这里我们引入spring-boot-starter-redis的依赖。

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-redis</artifactId>
            <version>1.4.2.RELEASE</version>
        </dependency>
#### 2.2 注入CacheManager及RedisTemplate
新建一个Redis的配置类，命名为RedisCacheConfig，使用javaconfig形式注入CacheManager及RedisTemplate。为了操作方便，我们采用了Jackson进行序列化。代码如下

        @Configuration
        @EnableCaching
        public class RedisCacheConfig {

            private static final Logger LOGGER = LoggerFactory.getLogger(RedisCacheConfig.class);

            @Bean
            public CacheManager cacheManager(RedisTemplate<?, ?> redisTemplate) {
                CacheManager cacheManager = new RedisCacheManager(redisTemplate);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Springboot Redis cacheManager 加载完成");
                }
                return cacheManager;
            }

            @Bean
            public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
                RedisTemplate<String, Object> template = new RedisTemplate<>();
                template.setConnectionFactory(factory);

                //使用Jackson2JsonRedisSerializer来序列化和反序列化redis的value值（默认使用JDK的序列化方式）
                Jackson2JsonRedisSerializer serializer = new Jackson2JsonRedisSerializer(Object.class);

                ObjectMapper mapper = new ObjectMapper();
                mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
                mapper.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);
                serializer.setObjectMapper(mapper);

                template.setValueSerializer(serializer);
                //使用StringRedisSerializer来序列化和反序列化redis的key值
                template.setKeySerializer(new StringRedisSerializer());
                template.afterPropertiesSet();
                LOGGER.info("Springboot RedisTemplate 加载完成");
                return template;
            }
        }

**注意** 要使用 **@Configuration** 标注此类为一个配置类，当然你可以使用 **@Component**， 但是不推荐，原因在于 **@Component** 注解虽然也可以当作配置类，但是并不会为其生成CGLIB代理Class，而使用**@Configuration**，CGLIB会为其生成代理类，进行性能的提升。

#### 2.3 调用方application.propertie需要增加Redis配置
我们的包开发完毕之后，调用方的application.properties需要进行相关配置如下：

        #单机模式redis
        spring.redis.host=127.0.0.1
        spring.redis.port=6379
        spring.redis.pool.maxActive=8
        spring.redis.pool.maxWait=-1
        spring.redis.pool.maxIdle=8
        spring.redis.pool.minIdle=0
        spring.redis.timeout=10000
        spring.redis.password=

如果有密码的话，配置password即可。

这里为单机配置，如果需要支持哨兵集群，则配置如下，Java代码不需要改动，只需要变动配置即可。**注意** 两种配置不能共存！

        #哨兵集群模式
        # database name
        spring.redis.database=0
        # server password 密码，如果没有设置可不配
        spring.redis.password=
        # pool settings ...池配置
        spring.redis.pool.max-idle=8
        spring.redis.pool.min-idle=0
        spring.redis.pool.max-active=8
        spring.redis.pool.max-wait=-1
        # name of Redis server  哨兵监听的Redis server的名称
        spring.redis.sentinel.master=mymaster
        # comma-separated list of host:port pairs  哨兵的配置列表
        spring.redis.sentinel.nodes=127.0.0.1:26379,127.0.0.1:26479,127.0.0.1:26579


### 3. 定义注解
为了调用方便，我们定义一个名为**RateLimiter** 的注解，内容如下

            /**
            * @author snowalker
            * @version 1.0
            * @date 2018/10/27 1:25
            * @className RateLimiter
            * @desc 限流注解
            */
            @Target(ElementType.METHOD)
            @Retention(RetentionPolicy.RUNTIME)
            @Documented
            public @interface RateLimiter {

                /**
                * 限流key
                * @return
                */
                String key() default "rate:limiter";
                /**
                * 单位时间限制通过请求数
                * @return
                */
                long limit() default 10;

                /**
                * 过期时间，单位秒
                * @return
                */
                long expire() default 1;
            }

该注解明确只用于方法，主要有三个属性。
1. key--表示限流模块名，指定该值用于区分不同应用，不同场景，推荐格式为：应用名:模块名:ip:接口名:方法名
2. limit--表示单位时间允许通过的请求数
3. expire--incr的值的过期时间，业务中表示限流的单位时间。
### 4. 解析注解
定义好注解后，需要开发注解使用的切面，这里我们直接使用aspectj进行切面的开发。先看代码

        @Aspect
        @Component
        public class RateLimterHandler {

            private static final Logger LOGGER = LoggerFactory.getLogger(RateLimterHandler.class);

            @Autowired
            RedisTemplate redisTemplate;

            private DefaultRedisScript<Long> getRedisScript;

            @PostConstruct
            public void init() {
                getRedisScript = new DefaultRedisScript<>();
                getRedisScript.setResultType(Long.class);
                getRedisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("rateLimter.lua")));
                LOGGER.info("RateLimterHandler[分布式限流处理器]脚本加载完成");
            }

这里是注入了RedisTemplate，使用其API进行Lua脚本的调用。

init() 方法在应用启动时会初始化DefaultRedisScript，并加载Lua脚本，方便进行调用。

PS: Lua脚本放置在classpath下，通过ClassPathResource进行加载。


            @Pointcut("@annotation(com.snowalker.shield.ratelimiter.core.annotation.RateLimiter)")
            public void rateLimiter() {}

这里我们定义了一个切点，表示只要注解了 **@RateLimiter** 的方法，均可以触发限流操作。

            @Around("@annotation(rateLimiter)")
            public Object around(ProceedingJoinPoint proceedingJoinPoint, RateLimiter rateLimiter) throws Throwable {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("RateLimterHandler[分布式限流处理器]开始执行限流操作");
                }
                Signature signature = proceedingJoinPoint.getSignature();
                if (!(signature instanceof MethodSignature)) {
                    throw new IllegalArgumentException("the Annotation @RateLimter must used on method!");
                }
                /**
                * 获取注解参数
                */
                // 限流模块key
                String limitKey = rateLimiter.key();
                Preconditions.checkNotNull(limitKey);
                // 限流阈值
                long limitTimes = rateLimiter.limit();
                // 限流超时时间
                long expireTime = rateLimiter.expire();
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("RateLimterHandler[分布式限流处理器]参数值为-limitTimes={},limitTimeout={}", limitTimes, expireTime);
                }
                /**
                * 执行Lua脚本
                */
                List<String> keyList = new ArrayList();
                // 设置key值为注解中的值
                keyList.add(limitKey);
                /**
                * 调用脚本并执行
                */
                Long result = (Long) redisTemplate.execute(getRedisScript, keyList, expireTime, limitTimes);
                if (result == 0) {
                    String msg = "由于超过单位时间=" + expireTime + "-允许的请求次数=" + limitTimes + "[触发限流]";
                    LOGGER.debug(msg);
                    return "false";
                }
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("RateLimterHandler[分布式限流处理器]限流执行结果-result={},请求[正常]响应", result);
                }
                return proceedingJoinPoint.proceed();
            }
        }

这段代码的逻辑为，获取  **@RateLimiter** 注解配置的属性：key、limit、expire，并通过 **redisTemplate.execute(RedisScript<T> script, List<K> keys, Object... args)** 方法传递给Lua脚本进行限流相关操作，逻辑很清晰。

这里我们定义如果脚本返回状态为0则为触发限流，1表示正常请求。
### 5. Lua脚本
这里是我们整个限流操作的核心，通过执行一个Lua脚本进行限流的操作。脚本内容如下

        --获取KEY
        local key1 = KEYS[1]

        local val = redis.call('incr', key1)
        local ttl = redis.call('ttl', key1)

        --获取ARGV内的参数并打印
        local expire = ARGV[1]
        local times = ARGV[2]

        redis.log(redis.LOG_DEBUG,tostring(times))
        redis.log(redis.LOG_DEBUG,tostring(expire))

        redis.log(redis.LOG_NOTICE, "incr "..key1.." "..val);
        if val == 1 then
            redis.call('expire', key1, tonumber(expire))
        else
            if ttl == -1 then
                redis.call('expire', key1, tonumber(expire))
            end
        end

        if val > tonumber(times) then
            return 0
        end

        return 1

逻辑很通俗，我简单介绍下。

1. 首先脚本获取Java代码中传递而来的要限流的模块的key，不同的模块key值一定不能相同，否则会覆盖！
2. redis.call('incr', key1)对传入的key做incr操作，如果key首次生成，设置超时时间ARGV[1]；（初始值为1）
3. ttl是为防止某些key在未设置超时时间并长时间已经存在的情况下做的保护的判断；
4. 每次请求都会做+1操作，当限流的值val大于我们注解的阈值，则返回0表示已经超过请求限制，触发限流。否则为正常请求。

当过期后，又是新的一轮循环，整个过程是一个原子性的操作，能够保证单位时间不会超过我们预设的请求阈值。

到这里我们便可以在项目中进行测试。
## 测试
[demo地址](https://github.com/TaXueWWL/shleld-ratelimter/tree/master/shleld-ratelimter-demo)

这里我贴一下核心代码，我们定义一个接口，并注解    **@RateLimiter(key = "ratedemo:1.0.0", limit = 5, expire = 100)** 表示模块ratedemo:sendPayment:1.0.0 
在100s内允许通过5个请求，这里的参数设置是为了方便看结果。实际中，我们通常会设置1s内允许通过的次数。

        @Controller
        public class TestController {

            private static final Logger LOGGER = LoggerFactory.getLogger(TestController.class);

            @ResponseBody
            @RequestMapping("ratelimiter")
            @RateLimiter(key = "ratedemo:1.0.0", limit = 5, expire = 100)
            public String sendPayment(HttpServletRequest request) throws Exception {

                return "正常请求";
            }

        }

我们通过RestClient请求接口，日志返回如下：

        2018-10-28 00:00:00.602 DEBUG 17364 --- [nio-8888-exec-1] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]开始执行限流操作
        2018-10-28 00:00:00.688 DEBUG 17364 --- [nio-8888-exec-1] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]限流执行结果-result=1,请求[正常]响应

        2018-10-28 00:00:00.860 DEBUG 17364 --- [nio-8888-exec-3] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]开始执行限流操作
        2018-10-28 00:00:01.183 DEBUG 17364 --- [nio-8888-exec-4] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]开始执行限流操作
        2018-10-28 00:00:01.520 DEBUG 17364 --- [nio-8888-exec-3] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]限流执行结果-result=1,请求[正常]响应
        2018-10-28 00:00:01.521 DEBUG 17364 --- [nio-8888-exec-4] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]限流执行结果-result=1,请求[正常]响应

        2018-10-28 00:00:01.557 DEBUG 17364 --- [nio-8888-exec-5] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]开始执行限流操作
        2018-10-28 00:00:01.558 DEBUG 17364 --- [nio-8888-exec-5] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]限流执行结果-result=1,请求[正常]响应

        2018-10-28 00:00:01.774 DEBUG 17364 --- [nio-8888-exec-7] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]开始执行限流操作
        2018-10-28 00:00:02.111 DEBUG 17364 --- [nio-8888-exec-8] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]开始
        2018-10-28 00:00:02.169 DEBUG 17364 --- [nio-8888-exec-7] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]限流执行结果-result=1,请求[正常]响应

        2018-10-28 00:00:02.169 DEBUG 17364 --- [nio-8888-exec-8] c.s.s.r.core.handler.RateLimterHandler   :
         由于超过单位时间=100-允许的请求次数=5[触发限流]
        2018-10-28 00:00:02.276 DEBUG 17364 --- [io-8888-exec-10] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]开始执行限流操作
        2018-10-28 00:00:02.276 DEBUG 17364 --- [io-8888-exec-10] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]参数值为-limitTimes=5,limitTimeout=100
        2018-10-28 00:00:02.278 DEBUG 17364 --- [io-8888-exec-10] c.s.s.r.core.handler.RateLimterHandler   :
         由于超过单位时间=100-允许的请求次数=5[触发限流]
        2018-10-28 00:00:02.445 DEBUG 17364 --- [nio-8888-exec-2] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]开始执行限流操作
        2018-10-28 00:00:02.445 DEBUG 17364 --- [nio-8888-exec-2] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]参数值为-limitTimes=5,limitTimeout=100
        2018-10-28 00:00:02.446 DEBUG 17364 --- [nio-8888-exec-2] c.s.s.r.core.handler.RateLimterHandler   :
         由于超过单位时间=100-允许的请求次数=5[触发限流]
        2018-10-28 00:00:02.628 DEBUG 17364 --- [nio-8888-exec-4] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]开始执行限流操作
        2018-10-28 00:00:02.628 DEBUG 17364 --- [nio-8888-exec-4] c.s.s.r.core.handler.RateLimterHandler   :
         RateLimterHandler[分布式限流处理器]参数值为-limitTimes=5,limitTimeout=100
        2018-10-28 00:00:02.629 DEBUG 17364 --- [nio-8888-exec-4] c.s.s.r.core.handler.RateLimterHandler   :
         由于超过单位时间=100-允许的请求次数=5[触发限流]

根据日志能够看到，正常请求5次后，返回限流触发，说明我们的逻辑生效，对前端而言也是可以看到false标记，表明我们的Lua脚本限流逻辑是正确的，这里具体返回什么标记需要调用方进行明确的定义。

## 总结
我们通过Redis的incr及expire功能特性，开发定义了一套基于注解的分布式限流操作，核心逻辑基于Lua保证了原子性。达到了很好的限流的目的，生产上，可以基于该特点进行定制自己的限流组件，当然你可以参考本文的代码，相信你写的一定比我的demo更好！
