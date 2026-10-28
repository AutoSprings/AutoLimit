package src.main.java;

import com.autolimit.annotation.RateLimit;
import com.autolimit.annotation.UserQuota;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class RateLimitTest {

    @RateLimit(fallbackRequests = 5)
    @GetMapping("/search")
    public String search() {
        return "results";
    }

    @RateLimit(value =
            @UserQuota(role = "PREMIUM", maxRequests = 100),
    fallbackRequests = 10)
    @GetMapping("/data")
    public String data() {
        return "data";
    }
}
