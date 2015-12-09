package com.galacticfog.gestalt.security.utils

import java.security.SecureRandom

object SecureIdGenerator {

  val random = new SecureRandom()

  val alpha36 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
  val alpha62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
  val alpha64 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/+"

  def genId36(len: Int) = {
    val id = new StringBuilder(len)
    (1 to len) foreach(_ => id += alpha36.charAt(random.nextInt(alpha36.length)))
    id.toString
  }

  def genId62(len: Int) = {
    val id = new StringBuilder(len)
    (1 to len) foreach(_ => id += alpha62.charAt(random.nextInt(alpha62.length)))
    id.toString
  }

  def genId64(len: Int) = {
    val id = new StringBuilder(len)
    (1 to len) foreach(_ => id += alpha64.charAt(random.nextInt(alpha64.length)))
    id.toString
  }

  def main(args: Array[String]) {
    val num = if (args.length > 0) args(0).toInt else 10
    for (i <- 1 to num) println(genId62(24))
  }

}
