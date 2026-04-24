const { createApp, ref, reactive, onMounted, computed } = Vue;

// axios 全局拦截
const http = axios.create({ baseURL: '', timeout: 30000 });
http.interceptors.request.use(cfg => {
    const t = localStorage.getItem('zalo_bg_token');
    if (t) cfg.headers['Authorization'] = 'Bearer ' + t;
    return cfg;
});
http.interceptors.response.use(
    resp => resp,
    err => {
        if (err.response && err.response.status === 401) {
            localStorage.removeItem('zalo_bg_token');
            localStorage.removeItem('zalo_bg_nick');
            window.location.reload();
        }
        ElementPlus.ElMessage.error((err.response && err.response.data && err.response.data.msg) || err.message);
        return Promise.reject(err);
    }
);

const App = {
    setup() {
        const token = ref(localStorage.getItem('zalo_bg_token') || '');
        const nickName = ref(localStorage.getItem('zalo_bg_nick') || '');
        const rememberedCred = (() => {
            try { return JSON.parse(localStorage.getItem('zalo_bg_cred') || 'null') || {}; }
            catch (e) { return {}; }
        })();
        const loginForm = reactive({
            username: rememberedCred.username || '',
            password: rememberedCred.password || '',
            remember: !!rememberedCred.username
        });
        const loginLoading = ref(false);

        const activeMenu = ref('accounts');
        const menuTitle = k => ({ accounts: '账号管理', friends: '好友列表', messages: '聊天记录' }[k] || '');

        // ----- 账号 -----
        const accFilter = reactive({ zaloId:'', account:'', nickName:'', deptId:'', online:'', accountStatus:'' });
        const accList = ref([]); const accTotal = ref(0); const accPage = ref(1); const accSize = ref(20); const accLoading = ref(false);
        const resetAccFilter = () => { Object.keys(accFilter).forEach(k=>accFilter[k]=''); loadAccounts(1); };
        const loadAccounts = async (p) => {
            accPage.value = p || accPage.value;
            accLoading.value = true;
            try {
                const params = { page: accPage.value, size: accSize.value };
                for (const k of Object.keys(accFilter)) if (accFilter[k] !== '' && accFilter[k] !== null) params[k] = accFilter[k];
                const { data } = await http.get('/api/admin/accounts', { params });
                accList.value = data.data.records || [];
                accTotal.value = data.data.total || 0;
            } finally { accLoading.value = false; }
        };

        // ----- 好友 -----
        const friFilter = reactive({ ownerZaloId:'', friendUserId:'', displayName:'', phone:'', fstatus:'', deptId:'' });
        const friList = ref([]); const friTotal = ref(0); const friPage = ref(1); const friSize = ref(20); const friLoading = ref(false);
        const resetFriFilter = () => { Object.keys(friFilter).forEach(k=>friFilter[k]=''); loadFriends(1); };
        const loadFriends = async (p) => {
            friPage.value = p || friPage.value;
            friLoading.value = true;
            try {
                const params = { page: friPage.value, size: friSize.value };
                for (const k of Object.keys(friFilter)) if (friFilter[k] !== '' && friFilter[k] !== null) params[k] = friFilter[k];
                const { data } = await http.get('/api/admin/friends', { params });
                friList.value = data.data.records || [];
                friTotal.value = data.data.total || 0;
            } finally { friLoading.value = false; }
        };

        // ----- 消息 -----
        const msgFilter = reactive({ ownerZaloId:'', peerUserId:'', groupId:'', msgType:'', direction:'', contentLike:'' });
        const msgDateRange = ref([]);
        const msgList = ref([]); const msgTotal = ref(0); const msgPage = ref(1); const msgSize = ref(20); const msgLoading = ref(false);
        const resetMsgFilter = () => {
            Object.keys(msgFilter).forEach(k=>msgFilter[k]='');
            msgDateRange.value = [];
            loadMessages(1);
        };
        const loadMessages = async (p) => {
            msgPage.value = p || msgPage.value;
            msgLoading.value = true;
            try {
                const params = { page: msgPage.value, size: msgSize.value };
                for (const k of Object.keys(msgFilter)) if (msgFilter[k] !== '' && msgFilter[k] !== null) params[k] = msgFilter[k];
                if (msgDateRange.value && msgDateRange.value.length === 2) {
                    params.startTime = msgDateRange.value[0];
                    params.endTime = msgDateRange.value[1];
                }
                const { data } = await http.get('/api/admin/messages', { params });
                msgList.value = data.data.records || [];
                msgTotal.value = data.data.total || 0;
            } finally { msgLoading.value = false; }
        };

        const switchMenu = (k) => {
            activeMenu.value = k;
            if (k === 'accounts') loadAccounts(1);
            if (k === 'friends')  loadFriends(1);
            if (k === 'messages') loadMessages(1);
        };

        const doLogin = async () => {
            if (!loginForm.username || !loginForm.password) {
                ElementPlus.ElMessage.warning('请输入用户名和密码');
                return;
            }
            loginLoading.value = true;
            try {
                const payload = { username: loginForm.username, password: loginForm.password };
                const { data } = await http.post('/api/auth/login', payload);
                if (data.code === 0) {
                    token.value = data.data.token;
                    nickName.value = data.data.nickName;
                    localStorage.setItem('zalo_bg_token', token.value);
                    localStorage.setItem('zalo_bg_nick', nickName.value);
                    if (loginForm.remember) {
                        localStorage.setItem('zalo_bg_cred', JSON.stringify({
                            username: loginForm.username,
                            password: loginForm.password
                        }));
                    } else {
                        localStorage.removeItem('zalo_bg_cred');
                    }
                    ElementPlus.ElMessage.success('登录成功');
                    loadAccounts(1);
                } else {
                    ElementPlus.ElMessage.error(data.msg);
                }
            } finally { loginLoading.value = false; }
        };

        const logout = () => {
            localStorage.removeItem('zalo_bg_token');
            localStorage.removeItem('zalo_bg_nick');
            token.value = '';
            nickName.value = '';
        };

        const fmtDate = (s) => {
            if (!s) return '';
            try {
                const d = new Date(s);
                if (isNaN(d.getTime())) return s;
                const pad = n => String(n).padStart(2,'0');
                return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
            } catch { return s; }
        };
        const sexLabel = v => ({0:'未知',1:'男',2:'女'}[v] || '-');
        const fstatusLabel = v => ({0:'陌生人',3:'好友',5:'非好友'}[v] || v);
        const msgTypeLabel = v => ({1:'文本',2:'图片',3:'语音',4:'视频',5:'系统',7:'贴图',8:'卡片'}[v] || v);

        onMounted(() => {
            if (token.value) loadAccounts(1);
        });

        return {
            token, nickName, loginForm, loginLoading, doLogin, logout,
            activeMenu, menuTitle, switchMenu,
            accFilter, accList, accTotal, accPage, accSize, accLoading, loadAccounts, resetAccFilter,
            friFilter, friList, friTotal, friPage, friSize, friLoading, loadFriends, resetFriFilter,
            msgFilter, msgDateRange, msgList, msgTotal, msgPage, msgSize, msgLoading, loadMessages, resetMsgFilter,
            fmtDate, sexLabel, fstatusLabel, msgTypeLabel
        };
    }
};

const app = createApp(App);
app.use(ElementPlus);
for (const [k, v] of Object.entries(ElementPlusIconsVue)) app.component(k, v);
app.mount('#app');
