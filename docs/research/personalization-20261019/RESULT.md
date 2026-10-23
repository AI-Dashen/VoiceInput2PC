# 本地语音输入法个性化进化可行性核查

2026-10-19；研究与只读探针，不改产品、不录音、不训练、不运行推理。

## 结论与状态
- 当前个人热词学习未实现；代码中没有热词参数或自学习闭环。
- 个人词汇参与解码：存在明确C++ logits→argmax插入点，THEORETICALLY POSSIBLE。不能直接套Sherpa transducer接口。
- 个性化词编码：真实本地词表＋现存BPE merge规则，6个个人词完成token编码及字节回读；PASS_STRUCTURAL_TOKENIZATION_ONLY。不等于ASR准确率提升，完整预分词正则和句中替代切分仍PENDING。
- 当前全模型微调：PENDING，现有执行器不支持，不能直接开一个训练开关。不是数学上永远不可训练。
- 冻结骨干训练小型输出适配层：THEORETICALLY POSSIBLE；MNN官方有冻结特征提取器只训练新增层范例；本ASR适配和收益未验。可以优先研究隐藏态上的小层，不必先恢复全模型浮点训练链。

## 真实检查
APK资产：159239060 bytes（含manifest），encoder_stream.mnn＋encoder.mnn.weight＋decoder-runtime.bin＋logit.mnn＋logit.mnn.weight＋vocab.txt。
自有decoder：IASRDEC1，12层、宽768、72投影、25归一化；只读packed权重，append/reset、KV cache，无loss/backward/optimizer。
independent_asr_engine.cpp finish_stream每步计算64000维logits，std::max_element选择一个token，最多64步；尚无beam、hotword、context trie。
JNI：prepare/recognize/beginStream/acceptStream/finishStream/cancelStream/release，未暴露个人词设置或训练方法。
MNN源码本机.runtime/MNN-3.6.1；实际CMakeCache MNN_BUILD_TRAIN=OFF。当前只编译推理不等于MNN官方没有训练能力。
APK词表不含merges，但本机已有tokenizer_asset_audit.py和既有模型研究资料可读取merges；本次仅内存读取未导出模型资产。probe-result.json含六词token ID与回读结果。
LocalAsrService音频保存在内存，finish/close清空；InputHistoryStore记录正文/时间/来源/设备/状态，没有audio_ref、raw_asr、corrected_text、纠错关系。现有普通最终文字不能直接作为口音训练样本。

## 推荐工程路线（尚未实施）
1. 经用户确定流程后记录原ASR、确认修正、同一编辑会话与撤回；不能把换一种说法当纠错。系统编辑器常限制跨App读取，所以用已知语音提交范围、选区修订和显式确认；未知时不自动学。密码框不采集。
2. 正确个人词表→相容token序列→在max_element前做受限短语偏置。必须限制首词触发、数值分差、EOS和特殊token；记录/撤销学习项，不做全局替换。贪心无法回退，beam需独立KV分支，不是免费加一个开关。
3. 先用同一批已授权音频回放：开/关偏置对比目标词召回、整体CER、非目标误插入、延迟、内存；不得仅用合成logits证明ASR成功。没有验证前不改默认识别路线。
4. 若偏置不足且用户需要口音适应，开启用户可控的纠错音频保留，形成音频/正确文字对。固定原骨干，探索末层hidden→低秩输出适配/小型重打分器；训练时用正确前缀teacher forcing，不拿错误生成轨迹当正确对齐。需要新训练实现、tokenizer匹配、留出集和模型回滚。
5. 若需内部LoRA/全图微调，必须补可求导结构、量化算子策略、训练配置、数据与导出数值一致性。现有自有原生算子没有反向，不能直接丢进MNN优化器。原始浮点checkpoint有帮助，但缺少它不证明冻结骨干+新增层绝对不可能。
6. 不安装其他Linux系统、不依赖云端、不修改原模型；手机训练时长/功耗尚UNKNOWN，不承诺亚秒或固定样本数。

## 专家与来源
Jev实际路由成功。Claude ask-claude实际成功25.7秒，赞成logits插入点与先采集/回放，提醒贪心、BPE与KV分支成本。Claude收到的事实是APK无merges；进一步本地核查发现研究资料已有merges，原“无法取得分词规则”结论不成立，已由真实探针纠正。没有把专家推测当成准确率验证。
Google网页上轮咨询提交后未返回；本轮未声称获得Gemini意见。

官方原始资料：
- https://github.com/alibaba/MNN/blob/master/docs/train/finetune.md （本机3.6.1同路径已阅读，冻结特征提取/仅训练新层示例）
- https://github.com/alibaba/MNN/blob/master/docs/train/optim.md （loss/SGD/ADAM）
- https://github.com/alibaba/MNN/blob/master/tools/train/source/grad/OpGrad.cpp （梯度注册；不代表自有C++算子自动有梯度）
- https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html （上下文偏置参考，本页明确限transducer+modified_beam_search，不能直接移植API）

## 已完成/未完成
完成源码核查、MNN训练官方资料核查、6词结构探针、Claude审查与落盘。
未实施热词引擎，未训练ASR，未证明口音适应，未修改输入法/编译/安装。下一工程应先做个人词解码A/B小实验，结果决定是否开发用户学习功能。
