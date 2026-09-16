package cn.edu.lostfound.service;

import cn.edu.lostfound.ai.LocalAiClient;
import cn.edu.lostfound.dto.AiDtos;
import cn.edu.lostfound.verification.VerificationApi;
import org.springframework.stereotype.Service;

@Service
public class AiService {
  private final LocalAiClient client;
  private final VerificationApi verification;
  public AiService(LocalAiClient client,VerificationApi verification){this.client=client;this.verification=verification;}
  public AiDtos.Result polish(Long actor,String content){return ask(actor,content,true);}
  public AiDtos.Result chat(Long actor,String question){return ask(actor,question,false);}
  private AiDtos.Result ask(Long actor,String input,boolean polish){
    if(input==null||input.isBlank()||input.length()>3000)throw new IllegalArgumentException("AI输入须为1–3000字符非空文本");
    verification.requireEligible(actor);
    // No transaction/row lock during generation; no repository data appended.
    var result=client.generate(input,polish);
    verification.requireEligible(actor); // Discard result after revocation/expiry during generation.
    return result;
  }
}
