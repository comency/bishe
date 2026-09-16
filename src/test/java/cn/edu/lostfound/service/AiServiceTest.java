package cn.edu.lostfound.service;

import cn.edu.lostfound.ai.LocalAiClient;
import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.dto.AiDtos;
import cn.edu.lostfound.verification.VerificationApi;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiServiceTest {
  LocalAiClient client=mock(LocalAiClient.class);
  VerificationApi verification=mock(VerificationApi.class);
  AiService service=new AiService(client,verification);
  @Test void inputIsValidatedBeforeProvider(){assertThatThrownBy(()->service.polish(2L," ")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.chat(2L,"a".repeat(3001))).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(client,verification);}
  @Test void initialIneligibilityDoesNotCallModel(){doThrow(new BusinessException(403,"VERIFICATION_REQUIRED","Synthetic")).when(verification).requireEligible(2L);assertThatThrownBy(()->service.chat(2L,"synthetic")).isInstanceOf(BusinessException.class);verifyNoInteractions(client);}
  @Test void resultRequiresSecondEligibilityCheck(){when(client.generate("synthetic",true)).thenReturn(new AiDtos.Result("Synthetic","GENERATED",null));assertThat(service.polish(2L,"synthetic").status()).isEqualTo("GENERATED");var order=inOrder(verification,client);order.verify(verification).requireEligible(2L);order.verify(client).generate("synthetic",true);order.verify(verification).requireEligible(2L);}
  @Test void revokedDuringGenerationDiscardsResult(){doNothing().doThrow(new BusinessException(403,"VERIFICATION_REQUIRED","Synthetic")).when(verification).requireEligible(2L);when(client.generate("synthetic",false)).thenReturn(new AiDtos.Result("Must not return","GENERATED",null));assertThatThrownBy(()->service.chat(2L,"synthetic")).isInstanceOf(BusinessException.class);}
  @Test void dependencyFailureNeverBecomesSuccessfulFallback(){doNothing().doThrow(new org.springframework.dao.DataAccessResourceFailureException("Synthetic")).when(verification).requireEligible(2L);when(client.generate("synthetic",false)).thenReturn(AiDtos.Result.unavailable("DISABLED"));assertThatThrownBy(()->service.chat(2L,"synthetic")).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);}
}
