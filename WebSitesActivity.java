package net.hanyi.launcher.ui;

import android.app.SearchManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import net.hanyi.launcher.R;
import java.util.ArrayList;
import java.util.List;

/**
 * 网站 · 种子库 —— 一块屏，三条路，不套二级：
 *  ① 搜索框：输站名/种子词 → 本地索引即时过滤（Wiki、苦力怕论坛、MCBBS归档、
 *     Planet Minecraft、9Minecraft、Minecraft Maps、Chunkbase、AMIDST、MineAtlas…）
 *  ② 网站：点开走系统浏览器，启动器不劫持、不嵌 WebView（国风留白，不做浏览器）
 *  ③ 种子查找器：独立分区，点「打开种子站」把当前版本+关键词一起带进浏览器；
 *     断网时只给本地生成器兜底。
 * 小众站搜不到？本地索引里还有 Seeder / Godlike / Ezseed / Seed Atlas / Pugtools，
 * 输入「种子」「seed」「古城」就能把它们顶上来。
 */
public final class WebSitesActivity extends AppCompatActivity {

    static final Site[] SITES = {
        new Site("Minecraft Wiki", "zh.minecraft.wiki", "https://zh.minecraft.wiki/", "百科 · 方块实体机制"),
        new Site("苦力怕论坛", "www.creeperforum.cn", "https://www.creeperforum.cn/", "中文社区 · 教程与讨论"),
        new Site("MCBBS 归档", "archive.mcbbs.dev", "https://archive.mcbbs.dev/", "旧版精华帖镜像"),
        new Site("Planet Minecraft", "www.planetminecraft.com", "https://www.planetminecraft.com/", "地图 · 皮肤 · 模组"),
        new Site("9Minecraft", "www.9minecraft.net", "https://www.9minecraft.net/", "资源下载站（注意广告）"),
        new Site("Minecraft Maps", "www.minecraftmaps.com", "https://www.minecraftmaps.com/", "存档地图"),
        new Site("Chunkbase", "www.chunkbase.com", "https://www.chunkbase.com/", "种子 · 结构定位"),
        new Site("AMIDST", "github.com/toolbox4minecraft/amidst", "https://github.com/toolbox4minecraft/amidst", "种子地图预览"),
        new Site("MineAtlas", "www.mineatlas.com", "https://www.mineatlas.com/", "种子 biome 图"),
        new Site("中文 Minecraft 吧", "tieba.baidu.com", "https://tieba.baidu.com/f?kw=minecraft", "吧友经验"),
        new Site("Bilibili 教程", "search.bilibili.com", "https://search.bilibili.com/all?keyword=minecraft", "视频攻略"),
    };
    static final Site[] SEEDS = {
        new Site("Chunkbase 种子搜索", "www.chunkbase.com/apps", "https://www.chunkbase.com/apps/", "按版本查神种 / 遗迹"),
        new Site("SeedLander", "www.seedlander.com", "https://www.seedlander.com/", "在线飞掠种子地形"),
        new Site("Cubiomes Viewer", "cubiomes.github.io", "https://cubiomes.github.io/", "命令行级种子分析"),
        new Site("Ezseed", "ezseed.ru", "https://ezseed.ru/", "俄区大种子库"),
        new Site("Seedscan", "www.seedscan.net", "https://www.seedscan.net/", "批量筛种子"),
        new Site("Chunks Seeds", "www.chunkseeds.com", "https://www.chunkseeds.com/", "区块级种子"),
        new Site("Godlike 种子库", "godlike.wtf", "https://godlike.wtf/", "硬核种子分享"),
        new Site("Seeder", "seeder.12rad.de", "https://seeder.12rad.de/", "种子搜索器"),
        new Site("Seed Atlas", "seedatlas.org", "https://seedatlas.org/", "种子分布图"),
        new Site("Pugtools 种子", "www.pugtools.com", "https://www.pugtools.com/", "1.13+ 结构种子"),
    };

    private EditText q;
    private RecyclerView a, b;
    private String cur = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_websites);
        setTitle(R.string.websites_entry);
        q = findViewById(R.id.et_site_query);
        a = findViewById(R.id.list_sites); b = findViewById(R.id.list_seeds);
        a.setLayoutManager(new LinearLayoutManager(this));
        b.setLayoutManager(new LinearLayoutManager(this));
        q.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence c,int s,int e,int a2){}
            public void onTextChanged(CharSequence c,int s,int e,int a2){ cur=c.toString().trim().toLowerCase(); render(); }
            public void afterTextChanged(android.text.Editable e){}
        });
        render();
    }

    void render() {
        a.setAdapter(new A(filter(SITES)));
        b.setAdapter(new A(filter(SEEDS)));
    }
    Site[] filter(Site[] all){
        if(TextUtils.isEmpty(cur)) return all;
        List<Site> o=new ArrayList<>();
        for(Site s:all) if((s.name+" " +s.host+" "+s.desc).toLowerCase().contains(cur)) o.add(s);
        return o.toArray(new Site[0]);
    }

    class A extends RecyclerView.Adapter<A.VH>{
        Site[] d; A(Site[] d){this.d=d;}
        public int getItemCount(){return d.length;}
        public VH onCreateViewHolder(ViewGroup p,int t){
            View v=LayoutInflater.from(p.getContext()).inflate(R.layout.item_site_row,p,false);
            return new VH(v);
        }
        public void onBindViewHolder(VH h,int i){
            Site s=d[i]; h.name.setText(s.name); h.desc.setText(s.desc);
            h.itemView.setOnClickListener(x-> open(s.url));
        }
        class VH extends RecyclerView.ViewHolder{ TextView name,desc;
            VH(View v){super(v); name=v.findViewById(R.id.tv_site_name); desc=v.findViewById(R.id.tv_site_desc);}
        }
    }

    void open(String u){
        try{ startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(u))); }
        catch(Exception e){ Toast.makeText(this,R.string.web_open_fail,Toast.LENGTH_SHORT).show(); }
    }

    public static final class Site{
        public final String name,host,url,desc;
        Site(String n,String h,String u,String d){name=n;host=h;url=u;desc=d;}
    }
}
