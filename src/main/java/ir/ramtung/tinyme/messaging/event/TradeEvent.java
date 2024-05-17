package ir.ramtung.tinyme.messaging.event;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
public class TradeEvent extends Event{
    private LocalDateTime time;
    private String securityIsin;
    private int price;
    private int quantity;
    private long buyId;
    private long sellId;
}
