package com.xb.blog.article.controller;

import cn.hutool.core.util.StrUtil;
import com.xb.blog.article.common.constants.BehaviorType;
import com.xb.blog.article.common.utils.IpUtil;
import com.xb.blog.article.feign.SearchFeignService;
import com.xb.blog.article.feign.UserFeignService;
import com.xb.blog.article.service.ArticleService;
import com.xb.blog.article.service.CommentService;
import com.xb.blog.article.vo.ArticlePreviewVo;
import com.xb.blog.article.vo.CommentVo;
import com.xb.blog.common.core.constants.Result;
import com.xb.blog.common.core.constants.ResultEnum;
import com.xb.blog.common.core.dto.BehaviorLogDto;
import com.xb.blog.common.core.pojo.ArticleDocument;
import com.xb.blog.common.core.pojo.UserInfo;
import com.xb.blog.common.core.utils.UserUtil;
import com.xb.blog.common.redis.constants.RedisKeyConstants;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/preview")
public class PreviewController {

    @Autowired
    private ArticleService articleService;

    @Autowired
    private CommentService commentService;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private SearchFeignService searchFeignService;

    @Autowired
    private UserFeignService userFeignService;

    @GetMapping("/{id}")
    public Result getArticleById(HttpServletRequest request, @PathVariable("id") String id) {
        ArticlePreviewVo vo = articleService.getArticlePreviewById(id);
        if (vo != null) {
            vo.setIsAuthor(vo.getAuthorId().equals(UserUtil.getUserId()));

            //使用布隆过滤器统计点击量
            RBloomFilter<Object> bloomFilter = redissonClient.getBloomFilter(RedisKeyConstants.ARTICLE_CLICK_IP_FILTER + id);
            String userId = UserUtil.getUserId();
            String filterKey = IpUtil.getClientIp(request) + (StrUtil.isNotBlank(userId) ? "_" + userId : "");
            if (!bloomFilter.contains(filterKey)) {
                //代表这是一个新ip 返回的vo中点击数+1
                vo.setClickCount(vo.getClickCount() + 1);
                //更新文章的点击数
                articleService.updateClickCount(id, 1L);
                //更新es
                articleService.updateToEs(id);
                //将当前ip添加到布隆过滤器
                bloomFilter.add(filterKey);
            }

            //查询作者数据
            Result<UserInfo> result = userFeignService.getUserInfo(vo.getAuthorId());
            if (result.isSuccess()) {
                vo.setAuthorInfo(result.getData());
            }

            //保存行为数据到es
            ArticleDocument doc = articleService.getArticleDocumentByArticleId(id);
            if (StrUtil.isNotBlank(userId) && !userId.equals(doc.getAuthorId())) {
                BehaviorLogDto dto = new BehaviorLogDto();
                dto.setUserId(userId);
                dto.setBehaviorType(BehaviorType.READ.name());
                dto.setCategoryId(doc.getCategoryId());
                dto.setTagIds(doc.getTagIdList());
                dto.setTargetUserId(doc.getAuthorId());
                searchFeignService.saveBehaviorLog(dto);
            }

            return Result.success(vo);
        }
        return Result.build(ResultEnum.NO_EXISTS);
    }

    @GetMapping("/comment/{id}/{page}")
    public Result getById(@PathVariable("id") String id, @PathVariable("page") Long page) {
        CommentVo vo = commentService.getTreeDataById(id, null, page);
        return Result.success(vo);
    }
}
