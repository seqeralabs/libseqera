# lib-fixtures-redis

Redis test fixtures and Testcontainers integration for integration testing.

## Usage

Redis container setup and test data management:

```groovy
import io.seqera.fixtures.redis.RedisTestContainer
import io.micronaut.context.ApplicationContext
import spock.lang.Specification
import spock.lang.Shared

class RedisServiceTest extends Specification implements RedisTestContainer {
    
    @Shared
    ApplicationContext applicationContext
    
    @Shared
    RedisMessageQueue<String> messageQueue
    
    def setup() {
        applicationContext = ApplicationContext.run('test', 'redis')
        messageQueue = new RedisMessageQueue<>(String.class)
        sleep(500) // workaround to wait for Redis connection
    }
    
    def cleanup() {
        applicationContext.close()
    }
    
    def "should handle Redis message queue operations"() {
        given:
        def queueName = "test-queue"
        def message = "Hello World"
        
        when:
        messageQueue.offer(queueName, message)
        
        then:
        messageQueue.length(queueName) == 1
        
        when:
        def received = messageQueue.poll(queueName)
        
        then:
        received == message
        messageQueue.length(queueName) == 0
    }
    
    def "should handle Redis operations with Jedis directly"() {
        given:
        def jedis = new Jedis(redisHostName, redisPort as int)
        def key = "test-key"
        def value = "test-value"
        
        when:
        jedis.set(key, value)
        
        then:
        jedis.get(key) == value
        
        cleanup:
        jedis.close()
    }
}
```

## Testing

```bash
./gradlew :lib-fixtures-redis:test
```