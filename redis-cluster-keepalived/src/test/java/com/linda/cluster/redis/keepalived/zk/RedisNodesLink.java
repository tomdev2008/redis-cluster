package com.linda.cluster.redis.keepalived.zk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.linda.cluster.redis.common.bean.ClusterStateBean;
import com.linda.cluster.redis.common.bean.HostAndPort;
import com.linda.cluster.redis.common.utils.JSONUtils;

public class RedisNodesLink {
	
	/**
	 * 复制节点数据
	 * @param monitorNodes
	 * @return
	 */
	public static Map<String, HostAndPort> copy(Map<String, HostAndPort> monitorNodes){
		HashMap<String,HostAndPort> map = new HashMap<String,HostAndPort>();
		Set<String> keys = monitorNodes.keySet();
		for(String key:keys){
			try {
				map.put(key, (HostAndPort)(monitorNodes.get(key).clone()));
			} catch (CloneNotSupportedException e) {
				e.printStackTrace();
				//can't happen
			}
		}
		return map;
	}
	
	/**
	 * 找到改变数据的节点，方便更新zk data
	 * @param before
	 * @param after
	 * @return
	 */
	public static List<HostAndPort> getChanged(Map<String, HostAndPort> before,Map<String, HostAndPort> after){
		List<HostAndPort> list = new ArrayList<HostAndPort>();
		Set<String> keys = after.keySet();
		for(String key:keys){
			HostAndPort oh = before.get(key);
			HostAndPort nh = after.get(key);
			if(!nh.equals(oh)){
				list.add(nh);
			}
		}
		return list;
	}
	
	/**
	 * 选举master 节点，生成master slave 链表
	 * master->slave1->slave2->slave3,redis 解决redis master多个slave节点复制停顿问题
	 * @param monitorNodes
	 * @param bean
	 * @return
	 */
	public static HostAndPort linkRedisNodes(Map<String, HostAndPort> monitorNodes,ClusterStateBean bean){
		//链接节点
		Collection<HostAndPort> nodes = monitorNodes.values();
		List<HostAndPort> aliveNodes = new ArrayList<HostAndPort>();
		List<HostAndPort> offNodes = new ArrayList<HostAndPort>();
		for(HostAndPort node:nodes){
			String master = node.getMaster();
			//去除指向自己节点
			if(master!=null&&master.equals(node.getName())){
				node.setMaster(null);
				master = null;
			}
			if(node.isAlive()){
				aliveNodes.add(node);
				if(master!=null){
					HostAndPort masterNode = monitorNodes.get(master);
					if(masterNode!=null&&masterNode.isAlive()){
						HostAndPort next = masterNode.getNext();
						//多个节点指向同一个节点时，只能第一个，后面的算孤岛，单独的节点
						if(next!=null&&next.isAlive()){
							node.setMaster(null);
							node.setNext(null);
						}else{
							masterNode.setNext(node);
						}
					}
				}
			}else{
				offNodes.add(node);
			}
		}
		
		//清空失效宕机节点数据,方便设置zk节点状态
		for(HostAndPort offNode:offNodes){
			offNode.setMaster(null);
			offNode.setAlive(false);
			offNode.setNext(null);
		}
		
		//找到头节点和尾节点
		HostAndPort head = null;
		HostAndPort tail = null;
		List<HostAndPort> aliveSingleNodes = new ArrayList<HostAndPort>();
		for(HostAndPort alive:aliveNodes){
			String master = alive.getMaster();
			HostAndPort masterNode = monitorNodes.get(master);
			if(master==null||masterNode==null||!masterNode.isAlive()){
				alive.setMaster(null);
				HostAndPort next = alive.getNext();
				if(next!=null&&next.isAlive()){
					head = alive;
				}else{
					alive.setNext(null);
					aliveSingleNodes.add(alive);
				}
			}else{
				HostAndPort next = alive.getNext();
				if(next==null||!next.isAlive()){
					alive.setNext(null);
					tail = alive;
				}
			}
		}
		//将余下的节点连接起来
		for(HostAndPort host:aliveSingleNodes){
			host.setMaster(null);
			host.setNext(null);
			if(tail!=null){
				host.setMaster(tail.getName());
				tail.setNext(host);
				tail = host;
			}else{
				tail = host;
			}
			if(head==null){
				head = tail;
			}
		}
		
		//调整各节点master属性
		HostAndPort f = head;
		while(f!=null){
			HostAndPort next = f.getNext();
			if(next!=null){
				next.setMaster(f.getName());
			}
			f = next;
		}
		
		//根据现有master节点调整顺序
		HostAndPort nowMaster = monitorNodes.get(bean.getMaster());
		if(nowMaster==null||!nowMaster.isAlive()){
			return head;
		}else{
			if(head!=nowMaster){
				String master = nowMaster.getMaster();
				nowMaster.setMaster(null);
				monitorNodes.get(master).setNext(null);
				head.setMaster(tail.getName());
				tail.setNext(head);
			}
			return nowMaster;
		}
	}
	

}
