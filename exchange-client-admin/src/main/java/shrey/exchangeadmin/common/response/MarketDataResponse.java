package shrey.exchangeadmin.common.response;

import lombok.Getter;
import lombok.Setter;
import shrey.exchangeclient.cluster.BaseResponse;

@Getter
@Setter
public class MarketDataResponse extends BaseResponse {
    private Object data; // will hold our DTO data
    
    public MarketDataResponse(int code, String message, Object data) {
        super(code, message);
        this.data = data;
    }
}
