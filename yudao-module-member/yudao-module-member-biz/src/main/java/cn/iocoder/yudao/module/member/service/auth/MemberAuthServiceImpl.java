package cn.iocoder.yudao.module.member.service.auth;

import cn.hutool.core.lang.Assert;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.util.monitor.TracerUtils;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.authentication.MultiUsernamePasswordAuthenticationToken;
import cn.iocoder.yudao.module.member.controller.app.auth.vo.*;
import cn.iocoder.yudao.module.member.convert.auth.AuthConvert;
import cn.iocoder.yudao.module.member.dal.dataobject.user.MemberUserDO;
import cn.iocoder.yudao.module.member.dal.mysql.user.MemberUserMapper;
import cn.iocoder.yudao.module.member.service.user.MemberUserService;
import cn.iocoder.yudao.module.system.api.auth.UserSessionApi;
import cn.iocoder.yudao.module.system.api.logger.LoginLogApi;
import cn.iocoder.yudao.module.system.api.logger.dto.LoginLogCreateReqDTO;
import cn.iocoder.yudao.module.system.api.sms.SmsCodeApi;
import cn.iocoder.yudao.module.system.api.social.SocialUserApi;
import cn.iocoder.yudao.module.system.enums.logger.LoginLogTypeEnum;
import cn.iocoder.yudao.module.system.enums.logger.LoginResultEnum;
import cn.iocoder.yudao.module.system.enums.sms.SmsSceneEnum;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.servlet.ServletUtils.getClientIP;
import static cn.iocoder.yudao.module.member.enums.ErrorCodeConstants.*;

/**
 * ??????????????? Service ??????
 *
 * @author ????????????
 */
@Service
@Slf4j
public class MemberAuthServiceImpl implements MemberAuthService {

    @Resource
    @Lazy // ????????????????????????????????????????????????
    private AuthenticationManager authenticationManager;

    @Resource
    private MemberUserService userService;
    @Resource
    private SmsCodeApi smsCodeApi;
    @Resource
    private LoginLogApi loginLogApi;
    @Resource
    private UserSessionApi userSessionApi;
    @Resource
    private SocialUserApi socialUserApi;

    @Resource
    private PasswordEncoder passwordEncoder;
    @Resource
    private MemberUserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String mobile) throws UsernameNotFoundException {
        // ?????? username ????????? SysUserDO
        MemberUserDO user = userService.getUserByMobile(mobile);
        if (user == null) {
            throw new UsernameNotFoundException(mobile);
        }
        // ?????? LoginUser ??????
        return AuthConvert.INSTANCE.convert(user);
    }

    @Override
    public String login(AppAuthLoginReqVO reqVO, String userIp, String userAgent) {
        // ???????????? + ????????????????????????
        LoginUser loginUser = this.login0(reqVO.getMobile(), reqVO.getPassword());

        // ????????????????????? Redis ???????????? Token ??????
        return createUserSessionAfterLoginSuccess(loginUser, LoginLogTypeEnum.LOGIN_USERNAME, userIp, userAgent);
    }

    @Override
    @Transactional
    public String smsLogin(AppAuthSmsLoginReqVO reqVO, String userIp, String userAgent) {
        // ???????????????
        smsCodeApi.useSmsCode(AuthConvert.INSTANCE.convert(reqVO, SmsSceneEnum.MEMBER_LOGIN.getScene(), userIp));

        // ????????????????????????
        MemberUserDO user = userService.createUserIfAbsent(reqVO.getMobile(), userIp);
        Assert.notNull(user, "?????????????????????????????????");

        // ????????????
        LoginUser loginUser = AuthConvert.INSTANCE.convert(user);

        // ????????????????????? Redis ???????????? Token ??????
        return createUserSessionAfterLoginSuccess(loginUser, LoginLogTypeEnum.LOGIN_SMS, userIp, userAgent);
    }

    @Override
    public String socialQuickLogin(AppAuthSocialQuickLoginReqVO reqVO, String userIp, String userAgent) {
        // ?????? code ??????????????????????????????????????????????????????????????????
        Long userId = socialUserApi.getBindUserId(UserTypeEnum.MEMBER.getValue(), reqVO.getType(),
                reqVO.getCode(), reqVO.getState());
        if (userId == null) {
            throw exception(AUTH_THIRD_LOGIN_NOT_BIND);
        }

        // ????????????
        MemberUserDO user = userService.getUser(userId);
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }

        // ?????? LoginUser ??????
        LoginUser loginUser = AuthConvert.INSTANCE.convert(user);

        // ????????????????????? Redis ???????????? Token ??????
        return createUserSessionAfterLoginSuccess(loginUser, LoginLogTypeEnum.LOGIN_SOCIAL, userIp, userAgent);
    }

    @Override
    public String socialBindLogin(AppAuthSocialBindLoginReqVO reqVO, String userIp, String userAgent) {
        // ???????????????????????????????????????
        AppAuthSmsLoginReqVO loginReqVO = AppAuthSmsLoginReqVO.builder()
                .mobile(reqVO.getMobile()).code(reqVO.getSmsCode()).build();
        String token = this.smsLogin(loginReqVO, userIp, userAgent);
        LoginUser loginUser = userSessionApi.getLoginUser(token);

        // ??????????????????
        socialUserApi.bindSocialUser(AuthConvert.INSTANCE.convert(loginUser.getId(), getUserType().getValue(), reqVO));
        return token;
    }

    private String createUserSessionAfterLoginSuccess(LoginUser loginUser, LoginLogTypeEnum logType, String userIp, String userAgent) {
        // ??????????????????
        createLoginLog(loginUser.getUsername(), logType, LoginResultEnum.SUCCESS);
        // ????????????????????? Redis ???????????? Token ??????
        return userSessionApi.createUserSession(loginUser, userIp, userAgent);
    }

    @Override
    public String getSocialAuthorizeUrl(Integer type, String redirectUri) {
        return socialUserApi.getAuthorizeUrl(type, redirectUri);
    }

    private LoginUser login0(String username, String password) {
        final LoginLogTypeEnum logType = LoginLogTypeEnum.LOGIN_USERNAME;
        // ????????????
        Authentication authentication;
        try {
            // ?????? Spring Security ??? AuthenticationManager#authenticate(...) ???????????????????????????????????????
            // ??????????????????????????? loadUserByUsername ??????????????? User ??????
            authentication = authenticationManager.authenticate(new MultiUsernamePasswordAuthenticationToken(
                    username, password, getUserType()));
        } catch (BadCredentialsException badCredentialsException) {
            this.createLoginLog(username, logType, LoginResultEnum.BAD_CREDENTIALS);
            throw exception(AUTH_LOGIN_BAD_CREDENTIALS);
        } catch (DisabledException disabledException) {
            this.createLoginLog(username, logType, LoginResultEnum.USER_DISABLED);
            throw exception(AUTH_LOGIN_USER_DISABLED);
        } catch (AuthenticationException authenticationException) {
            log.error("[login0][username({}) ??????????????????]", username, authenticationException);
            this.createLoginLog(username, logType, LoginResultEnum.UNKNOWN_ERROR);
            throw exception(AUTH_LOGIN_FAIL_UNKNOWN);
        }
        Assert.notNull(authentication.getPrincipal(), "Principal ????????????");
        return (LoginUser) authentication.getPrincipal();
    }

    private void createLoginLog(String mobile, LoginLogTypeEnum logType, LoginResultEnum loginResult) {
        // ????????????
        MemberUserDO user = userService.getUserByMobile(mobile);
        // ??????????????????
        LoginLogCreateReqDTO reqDTO = new LoginLogCreateReqDTO();
        reqDTO.setLogType(logType.getType());
        reqDTO.setTraceId(TracerUtils.getTraceId());
        if (user != null) {
            reqDTO.setUserId(user.getId());
        }
        reqDTO.setUserType(getUserType().getValue());
        reqDTO.setUsername(mobile);
        reqDTO.setUserAgent(ServletUtils.getUserAgent());
        reqDTO.setUserIp(getClientIP());
        reqDTO.setResult(loginResult.getResult());
        loginLogApi.createLoginLog(reqDTO);
        // ????????????????????????
        if (user != null && Objects.equals(LoginResultEnum.SUCCESS.getResult(), loginResult.getResult())) {
            userService.updateUserLogin(user.getId(), getClientIP());
        }
    }

    @Override
    public LoginUser verifyTokenAndRefresh(String token) {
        // ?????? LoginUser
        LoginUser loginUser = userSessionApi.getLoginUser(token);
        if (loginUser == null) {
            return null;
        }
        // ?????? LoginUser ??????
        this.refreshLoginUserCache(token, loginUser);
        return loginUser;
    }

    private void refreshLoginUserCache(String token, LoginUser loginUser) {
        // ??? 1/3 ??? Session ????????????????????? LoginUser ??????
        if (System.currentTimeMillis() - loginUser.getUpdateTime().getTime() <
                userSessionApi.getSessionTimeoutMillis() / 3) {
            return;
        }

        // ???????????? UserDO ??????
        MemberUserDO user = userService.getUser(loginUser.getId());
        if (user == null || CommonStatusEnum.DISABLE.getStatus().equals(user.getStatus())) {
            // ?????? token ????????????????????????????????????????????? token ??????????????????????????????????????????
            throw exception(AUTH_TOKEN_EXPIRED);
        }

        // ?????? LoginUser ??????
        userSessionApi.refreshUserSession(token, loginUser);
    }

    @Override
    public LoginUser mockLogin(Long userId) {
        // ??????????????????????????? UserDO
        MemberUserDO user = userService.getUser(userId);
        if (user == null) {
            throw new UsernameNotFoundException(String.valueOf(userId));
        }

        // ????????????
        this.createLoginLog(user.getMobile(), LoginLogTypeEnum.LOGIN_MOCK, LoginResultEnum.SUCCESS);

        // ?????? LoginUser ??????
        return AuthConvert.INSTANCE.convert(user);
    }

    @Override
    public void logout(String token) {
        // ??????????????????
        LoginUser loginUser = userSessionApi.getLoginUser(token);
        if (loginUser == null) {
            return;
        }
        // ?????? session
        userSessionApi.deleteUserSession(token);
        // ??????????????????
        this.createLogoutLog(loginUser.getId(), loginUser.getUsername());
    }

    @Override
    public UserTypeEnum getUserType() {
        return UserTypeEnum.MEMBER;
    }

    @Override
    public void updatePassword(Long userId, AppAuthUpdatePasswordReqVO reqVO) {
        // ???????????????
        MemberUserDO userDO = checkOldPassword(userId, reqVO.getOldPassword());

        // ??????????????????
        userMapper.updateById(MemberUserDO.builder().id(userDO.getId())
                .password(passwordEncoder.encode(reqVO.getPassword())).build());
    }

    @Override
    public void resetPassword(AppAuthResetPasswordReqVO reqVO) {
        // ????????????????????????
        MemberUserDO userDO = checkUserIfExists(reqVO.getMobile());

        // ???????????????
        smsCodeApi.useSmsCode(AuthConvert.INSTANCE.convert(reqVO, SmsSceneEnum.MEMBER_FORGET_PASSWORD,
                getClientIP()));

        // ????????????
        userMapper.updateById(MemberUserDO.builder().id(userDO.getId())
                .password(passwordEncoder.encode(reqVO.getPassword())).build());
    }

    @Override
    public void sendSmsCode(Long userId, AppAuthSmsSendReqVO reqVO) {
        // TODO ????????????????????????????????????????????????
        smsCodeApi.sendSmsCode(AuthConvert.INSTANCE.convert(reqVO).setCreateIp(getClientIP()));
    }

    /**
     * ???????????????
     *
     * @param id          ?????? id
     * @param oldPassword ?????????
     * @return MemberUserDO ????????????
     */
    @VisibleForTesting
    public MemberUserDO checkOldPassword(Long id, String oldPassword) {
        MemberUserDO user = userMapper.selectById(id);
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }
        // ?????????????????????????????????????????????
        if (!passwordEncoder.matches(oldPassword,user.getPassword())) {
            throw exception(USER_PASSWORD_FAILED);
        }
        return user;
    }

    public MemberUserDO checkUserIfExists(String mobile) {
        MemberUserDO user = userMapper.selectByMobile(mobile);
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }
        return user;
    }

    private void createLogoutLog(Long userId, String username) {
        LoginLogCreateReqDTO reqDTO = new LoginLogCreateReqDTO();
        reqDTO.setLogType(LoginLogTypeEnum.LOGOUT_SELF.getType());
        reqDTO.setTraceId(TracerUtils.getTraceId());
        reqDTO.setUserId(userId);
        reqDTO.setUserType(getUserType().getValue());
        reqDTO.setUsername(username);
        reqDTO.setUserAgent(ServletUtils.getUserAgent());
        reqDTO.setUserIp(getClientIP());
        reqDTO.setResult(LoginResultEnum.SUCCESS.getResult());
        loginLogApi.createLoginLog(reqDTO);
    }

}
