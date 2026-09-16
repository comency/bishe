package cn.edu.lostfound.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AiDtos {
  private AiDtos() {}

  public record Polish(@NotBlank @Size(max = 3000) String content) {}

  public record Chat(@NotBlank @Size(max = 3000) String question) {}

  public record Result(String content,String status,String reason) {
    public static Result unavailable(String reason){
      String message=switch(reason){
        case "DISABLED"->"智能辅助当前未启用，可以继续手动填写或查看使用说明。";
        case "RESOURCE_LIMIT"->"本地资源或文本容量不足，请缩短输入或继续手动操作。";
        case "BUSY"->"智能辅助正在处理其他请求，请稍后手动重试。";
        case "TIMEOUT"->"本次生成超时，原文未改变，可以继续手动操作。";
        case "EMPTY_RESULT"->"未获得有效建议，原文未改变。";
        default->"智能辅助暂不可用，原文未改变，请继续手动操作。";
      };
      return new Result(message,"UNAVAILABLE",reason);
    }
  }
}
